package filodb.akkabootstrapper

import scala.concurrent.duration._
import scala.language.postfixOps
import scala.collection.immutable.Seq

import akka.actor.{ActorRef, Address, Props}
import akka.cluster.Cluster
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.pattern.ask
import akka.util.Timeout
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import spray.json._
import com.typesafe.scalalogging.StrictLogging

object AkkaBootstrapperMessages {

  final case object ClusterMembershipRequest

  final case class ClusterMembershipResponse(seeds: Seq[Address])

}

final case class DiscoveryTimeoutException(message: String, cause: Throwable = None.orNull)
  extends RuntimeException(message, cause)

final case class ClusterMembershipHttpResponse(members: List[String])

trait ClusterMembershipJsonSuppport {

  import DefaultJsonProtocol._

  implicit val printer = PrettyPrinter
  implicit val membershipJsonFormat = jsonFormat1(ClusterMembershipHttpResponse)

}

/**
  * This is the API facade for the Akka Bootstrapper Library
  *
  * @param cluster local cluster object to join with seeds
  */
class AkkaBootstrapper(protected val cluster: Cluster) extends StrictLogging with ClusterMembershipJsonSuppport {

  private implicit val system = cluster.system
  private[filodb] val settings = new AkkaBootstrapperSettings(cluster.system.settings.config)

  /**
    * Every instance that wants to join the akka cluster call this method.
    * This is a blocking call and does not return until akka cluster is formed or joined.
    *
    * It first checks if a cluster already exists to join by invoking the seeds endpoint.
    * If it exists, the seeds are used to join the existing cluster. Otherwise, it waits until
    * a configurable count of nodes come up and then forms a new akka cluster.
    *
    * Once this method returns, use the `getAkkaHttpRoute()` method to obtain the Akka
    * HTTP Route to expose as HTTP API on the application
    *
    * For full list of configuration and documentation, see the library's reference.conf file
    *
    * @throws filodb.akkabootstrapper.DiscoveryTimeoutException if the cluster bootstrapping times out
    *
    */
  @throws(classOf[DiscoveryTimeoutException])
  def bootstrap(): Unit = {
    val seeds = AkkaClusterSeedDiscovery(cluster, settings).discoverAkkaClusterSeeds
    logger.info(s"Joining seeds $seeds")
    cluster.joinSeedNodes(seeds)
    logger.info("Exited from AkkaBootstrapper.joinSeedNodes")
  }

  /**
    *
    * This call returns an Akka HTTP route which includes the seeds API. The caller is
    * responsible for starting an Akka HTTP server that includes this Route. This will
    * enable subsequent members to discover the formed cluster.
    *
    * @param membershipActor if the client already has an actor that tracks cluster membership, it can be reused here.
    *                        For a [[filodb.akkabootstrapper.AkkaBootstrapperMessages.ClusterMembershipRequest]]
    *                        message, it should respond with a
    *                        [[filodb.akkabootstrapper.AkkaBootstrapperMessages.ClusterMembershipResponse]]
    *                        message. If None is provided, then one is created internally.
    * @return The akka http Route that should be started by the caller once the method returns.
    */
  def getAkkaHttpRoute(membershipActor: Option[ActorRef] = None): Route = {
    val clusterMembershipTracker = membershipActor.getOrElse(system.actorOf(Props[ClusterMembershipTracker],
      name = "clusterListener"))
    val seedsContextPath = settings.seedsPath
    import AkkaBootstrapperMessages._

    implicit val executionContext = system.dispatcher
    val route =
      path(seedsContextPath) {
        get {
          implicit val timeout = Timeout(2 seconds) // TODO configurable timeout
          val seedNodes = (clusterMembershipTracker ask ClusterMembershipRequest).mapTo[ClusterMembershipResponse]

          complete {
            seedNodes map {
              resp => ClusterMembershipHttpResponse(resp.seeds.map(_.toString).sorted.toList)
            }
          }
        }
      }
    route
  }

  // If necessary, add support for other web frameworks.
}

object AkkaBootstrapper {
  def apply(cluster: Cluster): AkkaBootstrapper = new AkkaBootstrapper(cluster)
}


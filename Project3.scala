import akka.actor._
import scala.collection.mutable.ArrayBuffer
import scala.util.Random
import scala.math

case class route( msg : String, key : BigInt )
case class deliver( msg : String, key : BigInt )
case class IAmNeighbor(nrouting:ArrayBuffer[BigInt],nleaf:ArrayBuffer[BigInt]) /**send node table info*/

class IdRef {
  var ref : ActorRef = null;
  var id : BigInt = BigInt(-1);
}

object Pastry { 

  /**main method*/
  def main(args: Array[String]) = { 
    val b = 4 /**nodeid and keys are a sequence in base 2^b*/
    val base = math.pow(2,b).toInt
    val N = 100   /**number of nodes*/
    val system = ActorSystem("PastrySystem")
    var nodeArray = ArrayBuffer[ActorRef]()
    var counter = 0
    var randomID:BigInt = 0
    while(counter<N){ /**make nodes*/
      randomID = IDtoBigInt(genID(base),base)
      var nodey = system.actorOf(Props(classOf[Node],randomID), counter.toString)
      nodeArray = nodeArray += nodey
      counter += 1
    }
    system.shutdown
  }

  /**nodes*/
  class Node(id:BigInt) extends Actor {
    var R = ArrayBuffer[IdRef]()// routing array
    var L_small = ArrayBuffer[IdRef]()// leaf array, smaller than us , starting with smallest
    var L_large = ArrayBuffer[IdRef]()// leaf array, larger than us , starting with smallest
    var Rn : Int = 0; // the number of columns of R
    var Rm : Int = 0; // the number of rows of R
    def receive = {
      case route( msg : String, key : BigInt ) => {
	if (L_small(0) <= key && L_large( L_large.size - 1 ) >= key) {
	  //send to leaf with closest value to key, including ourselves
	  BigInt dist = (id - key).abs;
	  BigInt mdist = dist;
	  BigInt min = id;

	  for (int i = 0; i < L_small.size) {
	    dist = (L_small(i) - key).abs;
	    if (dist < mdist) {
	      mdist = dist;
	      min = L_small(i);
	    }
	  }

	  for (int i = 0; i < L_large.size) {
	    dist = (L_small(i) - key).abs;
	    if (dist < mdist) {
	      mdist = dist;
	      min = L_large(i);
	    }
	  }

	  //min is now id of closest leaf node, possibly including this one
	  
	}
      }

      case IAmNeighbor(nrouting:ArrayBuffer[BigInt],nleaf:ArrayBuffer[BigInt]) => {
	addRouting(nrouting)
	addLeaf(nleaf)
      }
    }
    def addRouting(nrouting:ArrayBuffer[BigInt]) = { /**add on to routing table*/
    }
    def addLeaf(nleaf:ArrayBuffer[BigInt]) = { /**add on to leaf tables*/
    }

    def index(i: Int, j: Int) {
      //return 
      return (i * Rn + j);
      
    }
  }

  /**generate random nodeid of length 32*/  
  def genID(base:Int):ArrayBuffer[Int] = { 
    val generator = new Random()
    var nodeID = new ArrayBuffer[Int]()

    var counter = 1
    while(counter<=32){ 
      nodeID = nodeID += (generator.nextInt(base.toInt))
      counter += 1
    } 
    return nodeID
  }

  /**convert node id to BigInt*/
  def IDtoBigInt(id:ArrayBuffer[Int], base:Int):BigInt = {
    var myint:BigInt = 0
    var index = 0
    while(index < id.length){
      var a = (BigInt(id(index)))*(BigInt(base).pow(index))
      myint += a
      index += 1
    }
    return myint
  }

  /**convert BigInt ID to sequence of length 32*/
  def BigInttoID(id: BigInt, base:Int):ArrayBuffer[Int]={
    var myArray = ArrayBuffer[Int]()
    var counter = 0
    var leftover = id
    val bbase = BigInt(base)
    while(counter<32){
      myArray = myArray += (leftover.mod(bbase)).toInt
      leftover = leftover/(bbase)
      counter += 1
    }
    return myArray
  }
}


import akka.actor._
import scala.collection.mutable.ArrayBuffer
import scala.util.Random
import scala.math

case class route( msg : String, key : BigInt )
case class deliver( msg : String, key : BigInt )
case class IAmNeighbor(nrouting:ArrayBuffer[IdRef],nL_small:ArrayBuffer[IdRef],nL_large:ArrayBuffer[IdRef],nID: IdRef) /**send node table info*/

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
      randomID = genID(base)
      var nodey = system.actorOf(Props(classOf[Node],randomID,base), counter.toString)
      nodeArray = nodeArray += nodey
      counter += 1
    }
    system.shutdown
  }

  /**nodes*/
  class Node(id:BigInt,base: Int) extends Actor {
    var R = ArrayBuffer[IdRef]()// routing array
    var L_small = ArrayBuffer[IdRef]()// leaf array, smaller than us , starting with smallest
    var L_large = ArrayBuffer[IdRef]()// leaf array, larger than us , starting with smallest
    var Rn : Int = 0; // the number of columns of R
    var Rm : Int = 0; // the number of rows of R
    def receive = {
      case route( msg : String, key : IdRef ) => {
	if (msg == "join") {
	  
	}
	if (L_small(0).id <= key.id && L_large( L_large.size - 1 ).id >= key.id) {
	  //send to leaf with closest value to key.id, including ourselves
	  var dist : BigInt = (id - key.id).abs;
	  var mdist : BigInt = dist;
	  var min :IdRef = new IdRef();
	  min.id = id;
	  min.ref = self;
	  var i : Int = 0;
	  for (i <- 0 until L_small.size) {
	    dist = (L_small(i).id - key.id).abs;
	    if (dist < mdist) {
	      mdist = dist;
	      min = L_small(i);
	    }
	  }

	  for (i <- 0 until L_large.size) {
	    dist = (L_large(i).id - key.id).abs;
	    if (dist < mdist) {
	      mdist = dist;
	      min = L_large(i);
	    }
	  }

	  //min is now id of closest leaf node, including this one
	  min.ref ! deliver( msg, key );
	}
	else {
	  //use routing table
	  var l : Int = shl( key.id, id );
	  var keyl:BigInt  = key.id % BigInt(16)^{l + 1}
	  keyl /= BigInt(16)^l
	  var keylINT = keyl.toInt

	  if (R( index(l, keylINT) ) != null ) {
	    //forward to node at this place in table
	    R( index( l, keylINT ) ).ref ! route( msg, key );
	    
	  }
	  else {
	    //rare case
	    //forward to a closer element, matching at least
	    //the same prefix, in the leaf set
	    //or routing table
	    var dist : BigInt = (id - key.id).abs;
	    var mdist : BigInt = dist;
	    var cont : Boolean = true;
	    var i : Int = 0;
	    //look through routing table first
	    for (i <- 0 until Rn) {
	      if (cont) {
		if (R( index( l, i ) ) != null) {
		  if (((R( index( l, i ) )).id - key.id).abs < mdist) {
		    cont = false;
		    R( index( l, i ) ).ref ! route(msg, key);
		  }
		}
	      }
	    }
	    for (i <- 0 until L_small.size) {
	      if (cont) {
		dist = (L_small(i).id - key.id).abs;
		if (dist < mdist) {
		  //must agree on at least prefix size l, other distance could not be less
		  cont = false;
		  L_small(i).ref ! route(msg,key)
		}
	      }
	    }

	    for (i <- 0 until L_large.size) {
	      dist = (L_large(i).id - key.id).abs;
	      if (dist < mdist) {
		cont = false;
		L_large(i).ref ! route(msg,key)
	      }
	    }

	    if (cont) //nobody is closer than us
	      self ! deliver( msg, key );

	  }
	}
      }
      case deliver( msg: String, key : IdRef ) => {
	printf("Node %d received message: ", id );
	println( msg );
      }

      case IAmNeighbor(nrouting:ArrayBuffer[IdRef],nL_small:ArrayBuffer[IdRef],nL_large:ArrayBuffer[IdRef],nID:IdRef) => { /**neighbor gives info*/
      }
    }
    def addToLeafs(leafs:ArrayBuffer[IdRef]){
      var i = 0
      while(i<leafs.length){/**sort each element in leafs*/
	if((leafs(i).id<id)&&(leafs(i).id>L_small(0).id)){ /**leafs(i) should be in small leafs*/
	  L_small(0)=leafs(i)
	  var j = 1
	  var temp:IdRef = null
	  while((leafs(i).id>L_small(j).id)&&(j<L_small.length)){/**sort leafs(i) into L_small array*/
	    temp=L_small(j)
	    L_small(j)=leafs(i)
	    L_small(j-1)=temp
	    j+=1
	  }
	}
	else if((leafs(i).id>id)&&(leafs(i).id<L_large(L_large.length-1).id)){ /**should be in large leafs*/
	  L_large(L_large.length-1)=leafs(i)
	  var j = L_large.length-2
	  var temp:IdRef = null
	  while((leafs(i).id<L_large(j).id)&&(j>=0)){/**sort leafs(i) into L_large array*/
	    temp=L_large(j)
	    L_large(j)=leafs(i)
	    L_large(j+1)=temp
	    j += -1
	  }
	}
       	i+=1
      }
    }
    def addToRouting(routing:ArrayBuffer[IdRef],nID:IdRef)={
      val neighborID = BigInttoArr(nID.id,base) /**find id sequences*/
      val myID = BigInttoArr(id,base)
      val matches = sequenceMatch(neighborID,myID)
      var i=1 /**row of routing*/
      var j=1 /**column of routing*/
      while(i<=32){ /**sequences are of length 32*/
	if(i<matches){
	  while(j<=base){ /**base possible entries for each spot in sequence*/
	    if((R(index(i,j)))==null){
	      R(index(i,j))=routing(index(i,j))
	    }
	    j+=1
	  }
	}
	else if (i>=matches){
	  while (j<= base) {
	    if(id!=routing(index(i,j)).id){/**make sure not the same node*/
	      var ijArray = BigInttoArr(routing(index(i,j)).id,base)
	      var m = sequenceMatch(myID,ijArray)
	      if((routing(index(m,ijArray(m+1))))==null){
		routing(index(m,ijArray(m+1))) = routing(index(i,j))
	      }
	    
	    }
	  }
	}
	i+=1
      }
    }
    def sequenceMatch(x:ArrayBuffer[Int],y:ArrayBuffer[Int]):Int ={ /**finds how long sequences match from beginning*/
      var i = x.length-1
      var matches = 0
      while((i>=0)&&(i<y.length)){
	if(x(i)==y(i)){
	  matches+=1
	}
	else{
	 i = -2 /**doesn't match, stop searching*/
	}
	i += -1
      }
      return matches
    }  
    
    
    def index(i: Int, j: Int) : Int = {
      //return 
      return (i * Rn + j);
      
    }
    def shl( x: BigInt, y: BigInt ) : Int = {
      var arrx : ArrayBuffer[Int] = BigInttoArr(x, 16);
      var arry : ArrayBuffer[Int] = BigInttoArr(y, 16);
      var i : Int = 0;
      var b : Boolean = (arrx(31 - i) == arry(31 - i));
      while (b) {
	if (i < 32) {
	  i += 1;
	  b = (arrx(31 - i) == arry(31 - i));
	}
	else {
	  i += 1;
	  b = false;
	}
	  
      }
      return i;
      
    }
  }

  /**generate random nodeid of length 32*/  
  def genID(base:Int): BigInt = { 
    val generator = new Random()
    var nodeID = new ArrayBuffer[Int]()

    var counter = 1
    while(counter<=32){ 
      nodeID = nodeID += (generator.nextInt(base.toInt))
      counter += 1
    } 
    return ArrtoBigInt(nodeID, base);
  }

  /**convert node id to BigInt*/
  def ArrtoBigInt(id:ArrayBuffer[Int], base:Int):BigInt = {
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
  def BigInttoArr(id: BigInt, base:Int):ArrayBuffer[Int]={
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


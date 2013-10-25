import akka.actor._
import scala.collection.mutable.ArrayBuffer
import scala.util.Random
import scala.math

case class route( msg : String, key : IdRef )
case class deliver( msg : String, key : IdRef )
case class IAmNeighbor(nrouting:ArrayBuffer[IdRef],nL_small:ArrayBuffer[IdRef],nL_large:ArrayBuffer[IdRef],nID: IdRef) /**send node table info*/
case class inittable( idin : IdRef, r_in : ArrayBuffer[IdRef] )
case class initializeLeafs(neighbor:IdRef,bigLeafs:ArrayBuffer[IdRef],smallLeafs:ArrayBuffer[IdRef])

class IdRef {
  var ref : ActorRef = null;
  var id : BigInt = BigInt(-1);
}


object Pastry { 
  class Node(id:BigInt,base: Int) extends Actor {
    var R = ArrayBuffer[IdRef]()// routing array
    var L_small = ArrayBuffer[IdRef]()// leaf array, smaller than us , starting with smallest
    L_small=L_small.padTo(base,null)
    var L_large = ArrayBuffer[IdRef]()// leaf array, larger than us , starting with smallest
    L_large=L_large.padTo(base,null)
    var Rn : Int = 0; // the number of columns of R
    var Rm : Int = 0; // the number of rows of R

    def receive = {

      case inittable( idin : IdRef, r_in : ArrayBuffer[IdRef] ) => {
	

	var l : Int = 0;
	//receiving this message means this node is currently in the
	//process of joining the network.
	//need to calculate how much we agree with the sender's id

	l = shl(id, idin.id);
	// l will be the row of this node's table which will be edited
	var i : Int = 0;
	// need to fetch the l nth digit of this node's id ( starting at 0th digit )
	var j : Int = getdigit( id, l );
	var k : Int = getdigit( idin, l );
	
	R( index( l, k ) ) = idin;
	for (i <- 0 until Rn) {
	  if (i != j) {
	    if (r_in( index(l, i) ) != null) {
	      R( index(l, i )) = r_in( index(l, i));
	    }
	  }
	}
	
      }
      case route( msg : String, key : IdRef ) => {
	if (msg == "join") {
	  var myid : IdRef  = new IdRef();
	  myid.id = id;
	  myid.ref = self;
	  key.ref ! inittable( myid, R );
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
	if (msg == "join") {
	  var Z : IdRef = new IdRef();
	  Z.id = id;
	  Z.ref = self;
	  key.ref ! inittable( Z, R );
	  key.ref ! initializeLeafs( Z , L_large , L_small)
	}
      }

      case IAmNeighbor(nrouting:ArrayBuffer[IdRef],nL_small:ArrayBuffer[IdRef],nL_large:ArrayBuffer[IdRef],nID:IdRef) => { /**neighbor gives info*/
      }

      case initializeLeafs(neighbor:IdRef,bigLeafs:ArrayBuffer[IdRef],smallLeafs:ArrayBuffer[IdRef]) => {
	L_small = smallLeafs
	L_large = bigLeafs
	if(neighbor.id>id){
	  addToLargeLeafs(neighbor)
	}else{
	  addToSmallLeafs(neighbor)
	}
      }

    }
    def addToSmallLeafs(leaf:IdRef){ /**add one leaf to small leafs*/
      var j=0
      if(L_small(0)==null){
	j+=1
	while((L_small(j)==null)&&(j<L_small.length)){
	  j+=1
	}/**j is first non null place*/
	L_small(j-1)=leaf
      }
      if((leaf.id<id)&&(leaf.id>L_small(j).id)){
	var temp:IdRef = null
	while((leaf.id>L_small(j).id)&&(j<L_small.length)){/**sort leafs(i) into L_small array*/
	  temp=L_small(j)
	  L_small(j)=leaf
	  L_small(j-1)=temp
	  j+=1
	}
      }
    }
    def addToLargeLeafs(leaf:IdRef){
      var j= L_large.length-1
      if(L_large(L_large.length-1)==null){
	j+= -1
	while((L_large(j)==null)&&(j>=0)){
	  j+= -1
	}/**j is first non null place*/
	L_small(j+1)=leaf
      }
      if((leaf.id>id)&&(leaf.id<L_large(j).id)){ 
	var temp:IdRef = null
	while((leaf.id<L_large(j).id)&&(j>=0)){
	  temp=L_large(j)
	  L_large(j)=leaf
	  L_large(j+1)=temp
	  j += -1
	}
      }
    }
    def addToRouting(routing:ArrayBuffer[IdRef],nID:IdRef)={
      val neighborID = BigInttoArr(nID.id,base) /**find id sequences*/
      val myID = BigInttoArr(id,base)
      val matches = sequenceMatch(neighborID,myID)/**same up to place matches-1*/
      var i=0 /**row of routing*/
      var j=0 /**column of routing*/
      while(i<=32){ /**sequences are of length 32, go through each row*/
	if(i<(matches-1)){
	  while(j<base){ /**copy entire row*/
	    if((R(index(i,j)))==null){
	      R(index(i,j))=routing(index(i,j))
	    }
	    j+=1
	  }
	}
	    else if (i==(matches-1)){/**copy row, except when column equals myID(matches)*/
	      while (j<base){
		if(((R(index(i,j)))==null)&&(j!=myID(matches))){
		  R(index(i,j))=routing(index(i,j))
		}
		j+=1
	      }
	    }
		else if (i>(matches-1)) {
		  while((routing(index(i,j))==null)&&(j<(base-1))) {
		    j+=1
		  }
		  
		  if(R(index(matches-1,neighborID(matches)))==null){
		    if (j != base)
		      R(index(matches-1,neighborID(matches))) = routing(index(i,j))
		  }
		}
	i+=1
	j=0
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

    def getdigit(m: BigInt, l: Int) : Int = {
      var tmp : BigInt  = m % BigInt(16)^{l + 1}
      tmp /= BigInt(16)^l
      val r : Int = tmp.toInt
      
      return r;
    }

  }



  /**main method*/
  def main(args: Array[String]) = { 
    val b = 4 /**nodeid and keys are a sequence in base 2^b*/
    val base = math.pow(2,b).toInt
    val N = 100   /**number of nodes*/
    val system = ActorSystem("PastrySystem")
    var nodeArray = ArrayBuffer[ActorRef]()
    var counter = 0
    var randomID:BigInt = 0
    var ids_generated : ArrayBuffer[BigInt] = ArrayBuffer();
    while(counter<N){ /**make nodes*/
      randomID = genID(base)
      while (ids_generated.contains(
      var nodey = system.actorOf(Props(classOf[Node],randomID,base), counter.toString)
      nodeArray = nodeArray += nodey
      counter += 1
    }
    system.shutdown
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

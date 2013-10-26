import akka.actor._
import scala.collection.mutable.ArrayBuffer
import scala.util.Random
import scala.math
import scala.concurrent.Await
import scala.concurrent.Future
import scala.concurrent.duration.Duration
import scala.concurrent.duration._
import akka.util.Timeout
import akka.pattern.ask

case class route( msg : String, key : IdRef )
case class deliver( msg : String, key : IdRef )
case class inittable( idin : IdRef, r_in : ArrayBuffer[IdRef] )
case class initializeLeafs(neighbor:IdRef,bigLeafs:ArrayBuffer[IdRef],smallLeafs:ArrayBuffer[IdRef])
case class bigLeaf(leaf:IdRef)
case class smallLeaf(leaf:IdRef)
case class join(node: ActorRef)
case class ReadyQuery
case class Printstate
case class Printrouting

class IdRef {
  var ref : ActorRef = null;
  var id : BigInt = BigInt(-1);
}


object Pastry { 
  class Node(id:BigInt,base: Int) extends Actor {
    var joined : Boolean = false; // whether this node has joined network
    var R = ArrayBuffer[IdRef]()// routing array
    var L_small = ArrayBuffer[IdRef]()// leaf array, smaller than us , starting with smallest

    var L_large = ArrayBuffer[IdRef]()// leaf array, larger than us , starting with smallest

    var Rn : Int = 0; // the number of columns of R
    var Rm : Int = 0; // the number of rows of R

    def receive = {
      case join(n) => {
	Rn = base;
	Rm = 32;
	R = R.padTo(base * 32, null);

	if (n != null) {
	  //otherwise, we are the first node
	  var myid : IdRef = new IdRef;
	  myid.id = id;
	  myid.ref = self;
	  n ! route( "join", myid );
	} else {
	  joined = true;
	}
      }

      case inittable( idin : IdRef, r_in : ArrayBuffer[IdRef] ) => {
	println("Receiving table")
	var l : Int = 0;
	//receiving this message means this node is currently in the
	//process of joining the network.
	//need to calculate how much we agree with the sender's id

	l = shl(id, idin.id);
	// l will be the row of this node's table which will be edited
	var i : Int = 0;
	// need to fetch the l th digit of this node's id ( starting at 0th digit )
	var j : Int = getdigit( id, l );
	var k : Int = getdigit( idin.id, l );
	
	R( index( l, k ) ) = idin;
	for (i <- 0 until Rn) {
	  if (i != j) {
	    if (r_in( index(l, i) ) != null) {
	      R( index(l, i )) = r_in( index(l, i));
	    }
	  }
	}
	
	println("table updated")
      }
      case route( msg : String, key : IdRef ) => {
	printf("Node %d received (%s, %d)\n", id, msg, key.id);
	if (msg == "join") {
	  var myid : IdRef  = new IdRef();
	  myid.id = id;
	  myid.ref = self;
	  key.ref ! inittable( myid, R );
	}
	
	var b2 : Boolean = false;
	// var smallindex = 0;
	// var largeindex = base - 1;
	// while (b2) {
	//   smallindex += 1;
	//   if (smallindex < base)
	//     b2 = (L_small(smallindex) == null)
	//   else
	//     b2 = false;
	// }

	// b2 = (L_small(largeindex) == null);
	// while (b2) {
	//   largeindex -= 1;
	//   if ( largeindex > -1 ) {
	//     b2 = (L_small(largeindex) == null)
	//   }
	//   else {
	//     b2 = false
	//   }
	// }
	
	if (L_large.isEmpty) {
	  if (L_small.isEmpty) {
	    b2 = true; //there are no other nodes in the network
	  } else {
	    b2 = (L_small(0).id <= key.id);
	  }
	} 
	else {
	  if (L_small.isEmpty) {
	    b2 = (L_large( L_large.size - 1).id >= key.id );
	  }
	  else {
	    b2 = (L_small(0).id <= key.id && L_large( L_large.size - 1 ).id >= key.id);
	  }
	}
	if (b2) {
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
	  println("Delivering to leaf node");
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
		printf("Forwarding to (l, k)'th entry in routing table\n");
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
		// for (i <- 0 until Rn) {
		//   if (cont) {
		//     if (R( index( l, i ) ) != null) {
		//       if (((R( index( l, i ) )).id - key.id).abs < mdist) {
		// 	cont = false;
		// 	R( index( l, i ) ).ref ! route(msg, key);
		//       }
		//     }
		//   }
		// }
		for (i <- 0 until L_small.size) {
		  if (cont && (L_small(i) != null)) {
		    dist = (L_small(i).id - key.id).abs;
		    if (dist < mdist) {
		      //must agree on at least prefix size l, other distance could not be less
		      cont = false;
		      println("Forwarding to leaf node")
		      L_small(i).ref ! route(msg,key)
		    }
		  }
		}

		for (i <- 0 until L_large.size) {
		  if (cont && L_large(i) != null) {
		    dist = (L_large(i).id - key.id).abs;
		    if (dist < mdist) {
		      cont = false;
		      println("Forwarding to leaf node")
		      L_large(i).ref ! route(msg,key)
		    }
		  }
		}

		if (cont) {//nobody is closer than us
		  println("Delivering to self")
		  self ! deliver( msg, key );
		}

	      }
	    }
      }
      case deliver( msg: String, key : IdRef ) => {
	printf("Node %d received message: ", id );
	println( msg );
	if (msg == "join") {
	  println("Sending state...")
	  var Z : IdRef = new IdRef();
	  Z.id = id;
	  Z.ref = self;
	  key.ref ! inittable( Z, R );
	  key.ref ! initializeLeafs( Z , L_large , L_small)
	}
      }
      case initializeLeafs(neighbor:IdRef,bigLeafs:ArrayBuffer[IdRef],smallLeafs:ArrayBuffer[IdRef]) => {

	L_small = smallLeafs.clone;
	L_large = bigLeafs.clone;

	if(neighbor.id>id){
	  addToLargeLeafs(neighbor)
	}else{
	  addToSmallLeafs(neighbor)
	}

	val myIdRef = new IdRef()
	myIdRef.ref = self
	myIdRef.id = id

	var i = 0
	for(i <- 0 until L_small.length){
	    L_small(i).ref ! bigLeaf(myIdRef)
	}
	var j = 0
	for(j <- 0 until L_large.length){
	    L_large(j).ref ! smallLeaf(myIdRef)
	}

	//the joining process is now complete
	printf("Node %d succesfully joins network\n", id);
	joined = true;
      }
      case bigLeaf(leaf:IdRef) => {
	addToLargeLeafs(leaf)
      }
      case smallLeaf(leaf:IdRef) => {
	addToSmallLeafs(leaf)
      }
      case ReadyQuery => {
	if (joined)
	  sender ! true
	else
	  sender ! false
      }
      case Printstate => {
	printf("Node %d\n", id);
	print("L_small:\n")
	var i : Int = 0;
	for (i <- 0 until L_small.size)
	  println(L_small(i).id)

	print("L_large:\n")
	for (i <- 0 until L_large.size)
	  println(L_large(i).id)


	sender ! true
	
      }
      case Printrouting => {
	var row = 0
	var column = 0
	println()
	println(R.length)
	while(row<32){
	  while(column<16){
	    if(R(16*row+column)!=null){
	      var tableSeq = BigInttoArr(R(16*row+column).id,base)
	      for(i <- 31 to 0) {
		print(tableSeq(i) + ",")
	      }	      
	    }
	    else print(null)
	    
	    print("   ")
	    column += 1
	  }
	  column = 0
	  println()
	  row += 1
	}
	sender ! true
      }
    }
    def addToSmallLeafs(leaf:IdRef){ /**add one leaf to small leafs*/
      var i : Int = 0;
      //add leaf
      L_small.prepend( leaf );
      //find correct spot
      while (i < L_small.size - 1) {
	if (L_small(i).id > L_small(i + 1).id) {
	  //swap them
	  var tmp = L_small(i)
	  L_small(i) = L_small(i + 1)
	  L_small(i + 1) = tmp

	}
	i += 1;
      }

      if (L_small.size > base) {
	//too many leafs
	//delete smallest
	L_small = L_small.drop(1);
      }
	
	
      
      // var sort:Boolean = false
      // if(L_small.length==0){/**empty, does not need to be sorted*/ 
      // 	L_small += leaf
      // }
      // else if(L_small.length<base){ /**not full*/
      // 	L_small.prepend(leaf)
      // 	sort = true
      // }
      // else if ((L_small.length==base)&&(leaf.id>L_small(0).id)){/**full, and the leaf is in range to be added*/ 
      // 	L_small(0)=leaf
      // 	sort = true
      // }
      // if(sort){ /**needs to be sorted*/ 
      // 	var j = 1
      // 	var temp:IdRef = null
      // 	while((leaf.id>L_small(j).id)&&(j<L_small.length)){/**sort leafs(i) into L_small array*/
      // 	  temp=L_small(j)
      // 	  L_small(j)=leaf
      // 	  L_small(j-1)=temp
      // 	  j+=1
      // 	}   
      // }
    }
    def addToLargeLeafs(leaf:IdRef){
      var i : Int = 0;
      //add leaf
      L_large.prepend( leaf );
      //find correct spot
      while (i < L_large.size - 1) {
	if (L_large(i).id > L_large(i + 1).id) {
	  //swap them
	  var tmp = L_large(i)
	  L_large(i) = L_large(i + 1)
	  L_large(i + 1) = tmp
	  
	}
	i += 1;

      }

      if (L_large.size > base) {
	//too many leafs
	//delete largest
	L_large = L_large.dropRight(1);
      }
    // var sort:Boolean = false
    //   if(L_large.length==0){/**empty, does not need to be sorted*/ 
    // 	L_large = L_large += leaf
    //   }
    //   else {
    // 	if(L_large.length<base){ /**not full*/
    // 	  L_large= L_large += leaf
    // 	  sort = true
    // 	}
    // 	else {

    // 	  if ((L_large.length==base)&&(leaf.id<L_large(L_large.length).id)){/**full, and the leaf is in range to be added*/ 
    // 	    L_large(L_large.length-1)=leaf
    // 	    sort = true
    // 	  }
    // 	}
    //   }

    //   if(sort){ /**needs to be sorted*/ 
    // 	var j = L_large.length-1
    // 	var temp:IdRef = null
    // 	while((leaf.id<L_large(j-1).id)&&(j>0)){/**sort leafs(i) into L_small array*/
    // 	  temp=L_large(j-1)
    // 	  L_large(j-1)=leaf
    // 	  L_large(j)=temp
    // 	  j+= -1
    // 	}   
    //   }
    }
    def addToRouting(routing:ArrayBuffer[IdRef],nID:IdRef)={
      val neighborID = BigInttoArr(nID.id,base) /**find id sequences*/
      val myID = BigInttoArr(id,base)
      val matches = sequenceMatch(neighborID,myID)/**same up to place matches-1*/
      var i=0 /**row of routing*/
      var j=0 /**column of routing*/
      while(i<=32){ /**sequences are of length 32, go through each row*/
	if(i<matches){
	  while(j<base){ /**copy entire row*/
	    if((R(index(i,j)))==null){
	      R(index(i,j))=routing(index(i,j))
	    }
	    j+=1
	  }
	}
	    else if (i==matches){/**copy row, except when column equals myID(matches)*/
	      while (j<base){
		if(((R(index(i,j)))==null)&&(j!=myID(matches))){
		  R(index(i,j))=routing(index(i,j))
		}
		j+=1
	      }
	    }
		else if (i>matches) {
		  while((routing(index(i,j))==null)&&(j<(base-1))) {
		    j+=1
		  }
		  
		  if(R(index(matches,neighborID(matches)))==null){
		    if (j != base)
		      R(index(matches,neighborID(matches))) = routing(index(i,j))
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
      if ((i * Rn +j ) > Rn * Rm - 1)
	printf( "(%d, %d) out of range\n", i, j)
      
      return (i * Rn + j);
      
    }
    def shl( x: BigInt, y: BigInt ) : Int = {
      var arrx : ArrayBuffer[Int] = BigInttoArr(x, 16);
      var arry : ArrayBuffer[Int] = BigInttoArr(y, 16);
      var i : Int = 0;
      var b : Boolean = (arrx(31 - i) == arry(31 - i));
      while (b) {
	if (i < 31) {
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
    val N : Int = args(0).toInt  /**number of nodes*/
    val system = ActorSystem("PastrySystem")
    var nodeArray = ArrayBuffer[ActorRef]()
    var counter = 0
    var randomID:BigInt = 0
    var ids_generated : ArrayBuffer[BigInt] = ArrayBuffer();
    while(counter<N){ /**make nodes*/
      randomID = genID(base) % BigInt(1000)
      while (ids_generated.contains( randomID )) {
	randomID = genID(base)
	
      }
      var nodey = system.actorOf(Props(classOf[Node],randomID,base), counter.toString)
      nodeArray = nodeArray += nodey
      counter += 1
    }
    //add first node
    nodeArray(0) ! join(null);  
  
    //add other nodes
    var i : Int = 0;
    for (i <- 1 until N) {
      nodeArray(i) ! join(nodeArray(i -1 ));  
      //wait for join process to complete
      implicit val timeout = Timeout(20 seconds)
      var isready: Boolean = false;
      while (!isready) {
	val future = nodeArray(i) ? ReadyQuery
	isready =  Await.result(future.mapTo[Boolean], timeout.duration )
      }
    }

    println("Done with setup.")

    //now let's print the system
    for (i <- 0 until N) {
      implicit val timeout = Timeout(20 seconds)
      var isready: Boolean = false;
      val future = nodeArray(i) ? Printstate
      println();
      isready =  Await.result(future.mapTo[Boolean], timeout.duration )
    }

    /**print routing tables*/
    for(i<-0 until N){
      println("Routing table for node " + i)
      implicit val timeout = Timeout(20 seconds)
      var isready: Boolean = false;
      val future = nodeArray(i) ? Printrouting
      println();
      isready =  Await.result(future.mapTo[Boolean], timeout.duration)
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


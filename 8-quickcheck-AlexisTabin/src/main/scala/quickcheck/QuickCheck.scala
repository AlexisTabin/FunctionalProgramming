package quickcheck

import common._

import org.scalacheck._
import Arbitrary._
import Gen._
import Prop._

abstract class QuickCheckHeap extends Properties("Heap") with IntHeap {

  lazy val genHeap: Gen[H] =
    for{
      elem : A <- arbitrary[A]
      heap : H <- oneOf( const(empty), genHeap)
    }yield insert(elem, heap)


  implicit lazy val arbHeap: Arbitrary[H] = Arbitrary(genHeap)

  property("gen1") = forAll { (h: H) =>
    val m = if (isEmpty(h)) 0 else findMin(h)
    findMin(insert(m, h)) == m
  }

  property("min1") = forAll { a: A =>
  val h = insert(a, empty)
  findMin(h) == a
}

  property("min2elem") = forAll{ (a : Int, b : Int) => 
     val h = insert(a, insert(b, empty))
     findMin(h) == Math.min(a,b)
  }

  property("deleteSingleHeap") = forAll{ a : Int =>
    val h = insert(a, empty)
    isEmpty(deleteMin(h))
  }
  
  property("sortedSequence") = forAll { heap : H => 
    def helper(sortedList : List[A], heaps : H): List[A] = {
     if (isEmpty(heaps)) sortedList
     else
      findMin(heaps) :: helper(sortedList, deleteMin(heaps))
    }
    val seq = helper(Nil, heap)
    seq == seq.sorted    
  }

  property("findMin2Heaps") = forAll {(h1 : H, h2 : H) =>
    val minMel = findMin(meld(h1,h2))
    val minH1H2 = Math.min(findMin(h1), findMin(h2))
    minMel == minH1H2
    }


}

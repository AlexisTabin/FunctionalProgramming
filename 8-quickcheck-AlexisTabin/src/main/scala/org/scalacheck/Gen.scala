/*-------------------------------------------------------------------------*\
 **  ScalaCheck                                                             **
 **  Copyright (c) 2007-2016 Rickard Nilsson. All rights reserved.          **
 **  http://www.scalacheck.org                                              **
 **                                                                         **
 **  This software is released under the terms of the Revised BSD License.  **
 **  There is NO WARRANTY. See the file LICENSE for the full text.          **
 \*------------------------------------------------------------------------ */

package org.scalacheck

import language.higherKinds
import scala.language.implicitConversions

import rng.Seed
import util.Buildable
import util.SerializableCanBuildFroms._

import scala.annotation.tailrec
import scala.collection.immutable.TreeMap
import scala.collection.mutable.ArrayBuffer

sealed abstract class Gen[+T] extends Serializable { self =>

  //// Private interface ////

  import Gen.{R, r, gen}

  /** Just an alias */
  private type P = Gen.Parameters

  /** Should be a copy of R.sieve. Used internally in Gen when some generators
   *  with suchThat-clause are created (when R is not available). This method
   *  actually breaks covariance, but since this method will only ever be
   *  called with a value of exactly type T, it is OK. */
  private[scalacheck] def sieveCopy(x: Any): Boolean = true

  private[scalacheck] def doApply(p: P, seed: Seed): R[T]

  //// Public interface ////

  /** A class supporting filtered operations. */
  final class WithFilter(p: T => Boolean) {
    def map[U](f: T => U): Gen[U] = Gen.this.suchThat(p).map(f)
    def flatMap[U](f: T => Gen[U]): Gen[U] = Gen.this.suchThat(p).flatMap(f)
    def withFilter(q: T => Boolean): WithFilter = Gen.this.withFilter(x => p(x) && q(x))
  }

  /** Evaluate this generator with the given parameters */
  def apply(p: Gen.Parameters, seed: Seed): Option[T] =
    doApply(p, seed).retrieve

  def doPureApply(p: Gen.Parameters, seed: Seed, retries: Int = 100): Gen.R[T] = {
    @tailrec def loop(r: Gen.R[T], i: Int): Gen.R[T] =
      if (r.retrieve.isDefined) r
      else if (i > 0) loop(r, i - 1)
      else throw new Gen.RetrievalError()
    loop(doApply(p, seed), retries)
  }

  /**
   * Evaluate this generator with the given parameters.
   *
   * The generator will attempt to generate a valid `T` value. If a
   * valid value is not produced it may retry several times,
   * determined by the `retries` parameter (which defaults to 100).
   *
   * If all the retries fail it will throw a `Gen.RetrievalError`
   * exception.
   */
  def pureApply(p: Gen.Parameters, seed: Seed, retries: Int = 100): T =
    doPureApply(p, seed, retries).retrieve.get

  /** Create a new generator by mapping the result of this generator */
  def map[U](f: T => U): Gen[U] = gen { (p, seed) => doApply(p, seed).map(f) }

  /** Create a new generator by flat-mapping the result of this generator */
  def flatMap[U](f: T => Gen[U]): Gen[U] = gen { (p, seed) =>
    val rt = doApply(p, seed)
    rt.flatMap(t => f(t).doApply(p, rt.seed))
  }

  @deprecated("Empty generators are discouraged.", "1.14.0")
  def flatten[U](implicit asOption: T => Option[U]): Gen[U] =
    map(asOption).collect { case s: Some[_] => s.get }

  /** Create a new generator that uses this generator to produce a value
   *  that fulfills the given condition. If the condition is not fulfilled,
   *  the generator fails (returns None). Also, make sure that the provided
   *  test property is side-effect free, eg it should not use external vars. */
  def filter(p: T => Boolean): Gen[T] = suchThat(p)

  /** Create a new generator that fails if the specified partial function
   *  is undefined for this generator's value, otherwise returns the result
   *  of the partial function applied to this generator's value. */
  @deprecated("Empty generators are discouraged.", "1.14.0")
  def collect[U](pf: PartialFunction[T,U]): Gen[U] =
    flatMap { t => Gen.fromOption(pf.lift(t)) }

  /** Creates a non-strict filtered version of this generator. */
  def withFilter(p: T => Boolean): WithFilter = new WithFilter(p)

  /** Create a new generator that uses this generator to produce a value
   *  that fulfills the given condition. If the condition is not fulfilled,
   *  the generator fails (returns None). Also, make sure that the provided
   *  test property is side-effect free, eg it should not use external vars.
   *  This method is identical to [Gen.filter]. */
  def suchThat(f: T => Boolean): Gen[T] = new Gen[T] {
    def doApply(p: P, seed: Seed) = {
      val res = Gen.this.doApply(p, seed)
      res.copy(s = { x:T => res.sieve(x) && f(x) })
    }
    override def sieveCopy(x: Any) =
      try Gen.this.sieveCopy(x) && f(x.asInstanceOf[T])
      catch { case _: java.lang.ClassCastException => false }
  }

  case class RetryUntilException(n: Int) extends RuntimeException(s"retryUntil failed after $n attempts")

  /**
   * Create a generator that calls this generator repeatedly until the
   * given condition is fulfilled. The generated value is then
   * returned. Make sure that the provided test property is
   * side-effect free (it should not use external vars).
   *
   * If the generator fails more than maxTries, a RetryUntilException
   * will be thrown.
   */
  def retryUntil(p: T => Boolean, maxTries: Int): Gen[T] = {
    require(maxTries > 0)
    def loop(params: P, seed: Seed, tries: Int): R[T] =
      if (tries > maxTries) throw RetryUntilException(tries) else {
        val r = self.doApply(params, seed)
        if (r.retrieve.exists(p)) r else loop(params, r.seed, tries + 1)
      }
    Gen.gen((params, seed) => loop(params, seed, 1))
  }

  /**
   * Create a generator that calls this generator repeatedly until the
   * given condition is fulfilled. The generated value is then
   * returned. Make sure that the provided test property is
   * side-effect free (it should not use external vars).
   *
   *
   * If the generator fails more than 10000 times, a
   * RetryUntilException will be thrown. You can call `retryUntil`
   * with a second parameter to change this number.
   */
  def retryUntil(p: T => Boolean): Gen[T] =
    retryUntil(p, 10000)

  def sample: Option[T] =
    doApply(Gen.Parameters.default, Seed.random()).retrieve

  /** Returns a new property that holds if and only if both this
   *  and the given generator generates the same result, or both
   *  generators generate no result.  */
  def ==[U](g: Gen[U]) = Prop { prms =>
    // test equality using a random seed
    val seed = Seed.random()
    val lhs = doApply(prms, seed).retrieve
    val rhs = g.doApply(prms, seed).retrieve
    if (lhs == rhs) Prop.proved(prms) else Prop.falsified(prms)
  }

  def !=[U](g: Gen[U]) = Prop.forAll(this)(r => Prop.forAll(g)(_ != r))

  def !==[U](g: Gen[U]) = Prop { prms =>
    // test inequality using a random seed
    val seed = Seed.random()
    val lhs = doApply(prms, seed).retrieve
    val rhs = g.doApply(prms, seed).retrieve
    if (lhs != rhs) Prop.proved(prms) else Prop.falsified(prms)
  }

  /** Put a label on the generator to make test reports clearer */
  def label(l: String): Gen[T] = new Gen[T] {
    def doApply(p: P, seed: Seed) = {
      val r = Gen.this.doApply(p, seed)
      r.copy(l = r.labels + l)
    }
    override def sieveCopy(x: Any) = Gen.this.sieveCopy(x)
  }

  /** Put a label on the generator to make test reports clearer */
  def :|(l: String) = label(l)

  /** Put a label on the generator to make test reports clearer */
  def |:(l: String) = label(l)

  /** Put a label on the generator to make test reports clearer */
  def :|(l: Symbol) = label(l.name)

  /** Put a label on the generator to make test reports clearer */
  def |:(l: Symbol) = label(l.name)

  /** Perform some RNG perturbation before generating */
  def withPerturb(f: Seed => Seed): Gen[T] =
    Gen.gen((p, seed) => doApply(p, f(seed)))
}

object Gen extends GenArities{

  //// Private interface ////

  import Arbitrary.arbitrary

  /** Just an alias */
  private type P = Parameters

  class RetrievalError extends RuntimeException("couldn't generate value")

  private[scalacheck] trait R[+T] {
    def labels: Set[String] = Set()
    def sieve[U >: T]: U => Boolean = _ => true
    protected def result: Option[T]
    def seed: Seed

    def retrieve: Option[T] = result.filter(sieve)

    def copy[U >: T](
      l: Set[String] = this.labels,
      s: U => Boolean = this.sieve,
      r: Option[U] = this.result,
      sd: Seed = this.seed
    ): R[U] = new R[U] {
      override val labels = l
      override def sieve[V >: U] = { (x: Any) =>
        try s(x.asInstanceOf[U])
        catch { case _: java.lang.ClassCastException => false }
      }
      val seed = sd
      val result = r
    }

    def map[U](f: T => U): R[U] = r(retrieve.map(f), seed).copy(l = labels)

    def flatMap[U](f: T => R[U]): R[U] = retrieve match {
      case None => r(None, seed).copy(l = labels)
      case Some(t) =>
        val r = f(t)
        r.copy(l = labels ++ r.labels, sd = r.seed)
    }
  }

  private[scalacheck] def r[T](r: Option[T], sd: Seed): R[T] = new R[T] {
    val result = r
    val seed = sd
  }

  /** Generator factory method */
  private[scalacheck] def gen[T](f: (P, Seed) => R[T]): Gen[T] = new Gen[T] {
    def doApply(p: P, seed: Seed) = f(p, seed)
  }

  //// Public interface ////

  /** Generator parameters, used by [[org.scalacheck.Gen.apply]] */
  sealed abstract class Parameters extends Serializable {

    /** The size of the generated value. Generator implementations are allowed
     *  to freely interpret (or ignore) this value. During test execution, the
     *  value of this parameter is controlled by [[Test.Parameters.minSize]] and
     *  [[Test.Parameters.maxSize]]. */
    val size: Int

    /** Create a copy of this [[Gen.Parameters]] instance with
     *  [[Gen.Parameters.size]] set to the specified value. */
    def withSize(size: Int): Parameters = cp(size = size)

    // private since we can't guarantee binary compatibility for this one
    private case class cp(size: Int = size) extends Parameters
  }

  /** Provides methods for creating [[org.scalacheck.Gen.Parameters]] values */
  object Parameters {
    /** Default generator parameters instance. */
    val default: Parameters = new Parameters {
      val size: Int = 100
    }
  }

  /** A wrapper type for range types */
  trait Choose[T] extends Serializable {
    /** Creates a generator that returns a value in the given inclusive range */
    def choose(min: T, max: T): Gen[T]
  }

  /** Provides implicit [[org.scalacheck.Gen.Choose]] instances */
  object Choose {

    class IllegalBoundsError[A](low: A, high: A)
        extends IllegalArgumentException(s"invalid bounds: low=$low, high=$high")

    /**
     * This method gets a ton of use -- so we want it to be as fast as
     * possible for many of our common cases.
     */
    private def chLng(l: Long, h: Long)(p: P, seed: Seed): R[Long] = {
      if (h < l) {
        throw new IllegalBoundsError(l, h)
      } else if (h == l) {
        const(l).doApply(p, seed)
      } else if (l == Long.MinValue && h == Long.MaxValue) {
        val (n, s) = seed.long
        r(Some(n), s)
      } else if (l == Int.MinValue && h == Int.MaxValue) {
        val (n, s) = seed.long
        r(Some(n.toInt.toLong), s)
      } else if (l == Short.MinValue && h == Short.MaxValue) {
        val (n, s) = seed.long
        r(Some(n.toShort.toLong), s)
      } else if (l == 0L && h == Char.MaxValue) {
        val (n, s) = seed.long
        r(Some(n.toChar.toLong), s)
      } else if (l == Byte.MinValue && h == Byte.MaxValue) {
        val (n, s) = seed.long
        r(Some(n.toByte.toLong), s)
      } else {
        val d = h - l + 1
        if (d <= 0) {
          var tpl = seed.long
          var n = tpl._1
          var s = tpl._2
          while (n < l || n > h) {
            tpl = s.long
            n = tpl._1
            s = tpl._2
          }
          r(Some(n), s)
        } else {
          val (n, s) = seed.long
          r(Some(l + (n & 0x7fffffffffffffffL) % d), s)
        }
      }
    }

    private def chDbl(l: Double, h: Double)(p: P, seed: Seed): R[Double] = {
      val d = h - l
      if (d < 0) {
        throw new IllegalBoundsError(l, h)
      } else if (d > Double.MaxValue) {
        val (x, seed2) = seed.long
        if (x < 0) chDbl(l, 0d)(p, seed2) else chDbl(0d, h)(p, seed2)
      } else if (d == 0) {
        r(Some(l), seed)
      } else {
        val (n, s) = seed.double
        r(Some(n * (h-l) + l), s)
      }
    }

    implicit val chooseLong: Choose[Long] =
      new Choose[Long] {
        def choose(low: Long, high: Long): Gen[Long] =
          if (low > high) throw new IllegalBoundsError(low, high)
        else gen(chLng(low,high))
      }

    implicit val chooseInt: Choose[Int] =
      Choose.xmap[Long, Int](_.toInt, _.toLong)

    implicit val chooseShort: Choose[Short] =
      Choose.xmap[Long, Short](_.toShort, _.toLong)

    implicit val chooseChar: Choose[Char] =
      Choose.xmap[Long, Char](_.toChar, _.toLong)
    implicit val chooseByte: Choose[Byte] =
      Choose.xmap[Long, Byte](_.toByte, _.toLong)

    implicit val chooseDouble: Choose[Double] =
      new Choose[Double] {
        def choose(low: Double, high: Double) =
          if (low > high) throw new IllegalBoundsError(low, high)
          else gen(chDbl(low,high))
      }

    implicit val chooseFloat: Choose[Float] =
      Choose.xmap[Double, Float](_.toFloat, _.toDouble)

    /** Transform a Choose[T] to a Choose[U] where T and U are two isomorphic
     *  types whose relationship is described by the provided transformation
     *  functions. (exponential functor map) */
    def xmap[T, U](from: T => U, to: U => T)(implicit c: Choose[T]): Choose[U] =
      new Choose[U] {
        def choose(low: U, high: U): Gen[U] =
          c.choose(to(low), to(high)).map(from)
      }
  }


  //// Various Generator Combinators ////

  /** A generator that always generates the given value */
  implicit def const[T](x: T): Gen[T] = gen((p, seed) => r(Some(x), seed))

  /** A generator that never generates a value */
  def fail[T]: Gen[T] = gen((p, seed) => failed[T](seed))

  /** A result that never contains a value */
  private[scalacheck] def failed[T](seed0: Seed): R[T] =
    new R[T] {
      val result: Option[T] = None
      override def sieve[U >: T]: U => Boolean = _ => false
      val seed = seed0
    }

  /** A generator that fails if the provided option value is undefined,
   *  otherwise just returns the value. */
  @deprecated("Empty generators are discouraged.", "1.14.0")
  def fromOption[T](o: Option[T]): Gen[T] = o match {
    case Some(t) => const(t)
    case None => fail
  }

  /** A generator that generates a random value in the given (inclusive)
   *  range. If the range is invalid, the generator will not generate
   *  any value. */
  def choose[T](min: T, max: T)(implicit c: Choose[T]): Gen[T] =
    c.choose(min, max)

  /** Sequences generators. If any of the given generators fails, the
   *  resulting generator will also fail. */
  def sequence[C,T](gs: Traversable[Gen[T]])(implicit b: Buildable[T,C]): Gen[C] = {
    val g = gen { (p, seed) =>
      gs.foldLeft(r(Some(Vector.empty[T]), seed)) {
        case (rs,g) =>
          val rt = g.doApply(p, rs.seed)
          rt.flatMap(t => rs.map(_ :+ t)).copy(sd = rt.seed)
      }
    }
    g.map(b.fromIterable)
  }

  /** Wraps a generator lazily. The given parameter is only evaluated once,
   *  and not until the wrapper generator is evaluated. */
  def lzy[T](g: => Gen[T]): Gen[T] = {
    lazy val h = g
    gen { (p, seed) => h.doApply(p, seed) }
  }

  /** Wraps a generator for later evaluation. The given parameter is
   *  evaluated each time the wrapper generator is evaluated.
   *  This has been deprecated in favor of [[org.scalacheck.Gen.delay]]. */
  @deprecated("Replaced with delay()", "1.13.0")
  def wrap[T](g: => Gen[T]): Gen[T] = delay(g)

  /** Wraps a generator for later evaluation. The given parameter is
   *  evaluated each time the wrapper generator is evaluated. */
  def delay[T](g: => Gen[T]): Gen[T] =
    gen { (p, seed) => g.doApply(p, seed) }

  /** Creates a generator that can access its generation parameters */
  def parameterized[T](f: Parameters => Gen[T]): Gen[T] =
    gen { (p, seed) => f(p).doApply(p, seed) }

  /** Creates a generator that can access its generation size */
  def sized[T](f: Int => Gen[T]): Gen[T] =
    gen { (p, seed) => f(p.size).doApply(p, seed) }

  /** A generator that returns the current generation size */
  lazy val size: Gen[Int] = sized { sz => sz }

  /** Creates a resized version of a generator */
  def resize[T](s: Int, g: Gen[T]) = gen((p, seed) => g.doApply(p.withSize(s), seed))

  /** Picks a random value from a list */
  def oneOf[T](xs: Seq[T]): Gen[T] =
    if (xs.isEmpty) {
      throw new IllegalArgumentException("oneOf called on empty collection")
    } else {
      val vector = xs.toVector
      choose(0, vector.size - 1).map(vector(_))
    }

  /** Picks a random value from a list */
  def oneOf[T](t0: T, t1: T, tn: T*): Gen[T] = oneOf(t0 +: t1 +: tn)

  /** Picks a random generator from a list */
  def oneOf[T](g0: Gen[T], g1: Gen[T], gn: Gen[T]*): Gen[T] = {
    val gs = g0 +: g1 +: gn
    choose(0,gs.size-1).flatMap(gs(_)).suchThat(x => gs.exists(_.sieveCopy(x)))
  }

  def oneOfX[T](g0: Gen[T], g1: Gen[T], gn: Gen[T]*): Gen[T] = {
    val gs = g0 +: g1 +: gn
    choose(0,gs.size-1).flatMap(gs(_)).suchThat(x => gs.exists(_.sieveCopy(x)))
  }

  /** Makes a generator result optional. Either `Some(T)` or `None` will be provided. */
  def option[T](g: Gen[T]): Gen[Option[T]] =
    oneOfX[Option[T]](some(g), None)

  /** A generator that returns `Some(T)` */
  def some[T](g: Gen[T]): Gen[Option[T]] =
    g.map(Some.apply)

  /** Chooses one of the given generators with a weighted random distribution */
  def frequency[T](gs: (Int, Gen[T])*): Gen[T] = {
    val filtered = gs.iterator.filter(_._1 > 0).toVector
    if (filtered.isEmpty) {
      throw new IllegalArgumentException("no items with positive weights")
    } else {
    var total = 0L
      val builder = TreeMap.newBuilder[Long, Gen[T]]
      filtered.foreach { case (weight, value) =>
        total += weight
        builder += ((total, value))
      }
      val tree = builder.result
      choose(1L, total).flatMap(r => tree.from(r).head._2).suchThat { x =>
        gs.exists(_._2.sieveCopy(x))
      }
    }
  }

  /** Implicit convenience method for using the `frequency` method
   *  like this:
   *  {{{
   *   frequency((1, "foo"), (3, "bar"))
   *  }}}
   */
  implicit def freqTuple[T](t: (Int,T)): (Int,Gen[T]) = (t._1, const(t._2))


  //// List Generators ////

  /** Generates a container of any Traversable type for which there exists an
   *  implicit [[org.scalacheck.util.Buildable]] instance. The elements in the
   *  container will be generated by the given generator. The size of the
   *  generated container is limited by `n`. Depending on what kind of container
   *  that is generated, the resulting container may contain fewer elements than
   *  `n`, but not more. If the given generator fails generating a value, the
   *  complete container generator will also fail. */
  def buildableOfN[C,T](n: Int, g: Gen[T])(implicit
    evb: Buildable[T,C], evt: C => Traversable[T]
  ): Gen[C] =
    sequence[C,T](Traversable.fill(n)(g)) suchThat { c =>
      // TODO: Can we guarantee c.size == n (See issue #89)?
      evt(c).forall(g.sieveCopy)
    }

  /** Generates a container of any Traversable type for which there exists an
   *  implicit [[org.scalacheck.util.Buildable]] instance. The elements in the
   *  container will be generated by the given generator. The size of the
   *  container is bounded by the size parameter used when generating values. */
  def buildableOf[C,T](g: Gen[T])(implicit
    evb: Buildable[T,C], evt: C => Traversable[T]
  ): Gen[C] =
    sized(s => choose(0, s max 0).flatMap(buildableOfN[C,T](_,g))) suchThat { c =>
      if (c == null) g.sieveCopy(null) else evt(c).forall(g.sieveCopy)
    }

  /** Generates a non-empty container of any Traversable type for which there
   *  exists an implicit [[org.scalacheck.util.Buildable]] instance. The
   *  elements in the container will be generated by the given generator. The
   *  size of the container is bounded by the size parameter used when
   *  generating values. */
  def nonEmptyBuildableOf[C,T](g: Gen[T])(implicit
    evb: Buildable[T,C], evt: C => Traversable[T]
  ): Gen[C] =
    sized(s => choose(1, s max 1).flatMap(buildableOfN[C,T](_,g))) suchThat(x => evt(x).size > 0)

  /** A convenience method for calling `buildableOfN[C[T],T](n,g)`. */
  def containerOfN[C[_],T](n: Int, g: Gen[T])(implicit
    evb: Buildable[T,C[T]], evt: C[T] => Traversable[T]
  ): Gen[C[T]] = buildableOfN[C[T],T](n,g)

  /** A convenience method for calling `buildableOf[C[T],T](g)`. */
  def containerOf[C[_],T](g: Gen[T])(implicit
    evb: Buildable[T,C[T]], evt: C[T] => Traversable[T]
  ): Gen[C[T]] = buildableOf[C[T],T](g)

  /** A convenience method for calling `nonEmptyBuildableOf[C[T],T](g)`. */
  def nonEmptyContainerOf[C[_],T](g: Gen[T])(implicit
    evb: Buildable[T,C[T]], evt: C[T] => Traversable[T]
  ): Gen[C[T]] = nonEmptyBuildableOf[C[T],T](g)

  /** Generates a list of random length. The maximum length depends on the
   *  size parameter. This method is equal to calling
   *  `containerOf[List,T](g)`. */
  def listOf[T](g: => Gen[T]) = buildableOf[List[T],T](g)

  /** Generates a non-empty list of random length. The maximum length depends
   *  on the size parameter. This method is equal to calling
   *  `nonEmptyContainerOf[List,T](g)`. */
  def nonEmptyListOf[T](g: => Gen[T]) = nonEmptyBuildableOf[List[T],T](g)

  /** Generates a list of the given length. This method is equal to calling
   *  `containerOfN[List,T](n,g)`. */
  def listOfN[T](n: Int, g: Gen[T]) = buildableOfN[List[T],T](n,g)

  /** Generates a map of random length. The maximum length depends on the
   *  size parameter. This method is equal to calling
   *  <code>containerOf[Map,T,U](g)</code>. */
  def mapOf[T,U](g: => Gen[(T,U)]) = buildableOf[Map[T,U],(T,U)](g)

  /** Generates a non-empty map of random length. The maximum length depends
   *  on the size parameter. This method is equal to calling
   *  <code>nonEmptyContainerOf[Map,T,U](g)</code>. */
  def nonEmptyMap[T,U](g: => Gen[(T,U)]) = nonEmptyBuildableOf[Map[T,U],(T,U)](g)

  /** Generates a map with at most the given number of elements. This method
   *  is equal to calling <code>containerOfN[Map,T,U](n,g)</code>. */
  def mapOfN[T,U](n: Int, g: Gen[(T,U)]) = buildableOfN[Map[T,U],(T,U)](n,g)

  /** Generates an infinite stream. */
  def infiniteStream[T](g: => Gen[T]): Gen[Stream[T]] = {
    def unfold[A, S](z: S)(f: S => Option[(A, S)]): Stream[A] = f(z) match {
      case Some((h, s)) => h #:: unfold(s)(f)
      case None => Stream.empty
    }
    gen { (p, seed0) =>
      new R[Stream[T]] {
        val result: Option[Stream[T]] = Some(unfold(seed0)(s => Some(g.pureApply(p, s), s.next)))
        val seed: Seed = seed0.next
      }
    }
  }

  /** A generator that picks a random number of elements from a list */
  def someOf[T](l: Iterable[T]) = choose(0,l.size).flatMap(pick(_,l))

  /** A generator that picks a random number of elements from a list */
  def someOf[T](g1: Gen[T], g2: Gen[T], gs: Gen[T]*) =
    choose(0, gs.length+2).flatMap(pick(_, g1, g2, gs: _*))

  /** A generator that picks a given number of elements from a list, randomly */
  def pick[T](n: Int, l: Iterable[T]): Gen[Seq[T]] = {
    if (n > l.size || n < 0) throw new IllegalArgumentException("!!!")
    else if (n == 0) Gen.const(Nil)
    else gen { (p, seed0) =>
      val buf = ArrayBuffer.empty[T]
      val it = l.iterator
      var seed = seed0
      var count = 0
      while (it.hasNext) {
        val t = it.next
        count += 1
        if (count <= n) {
          buf += t
        } else {
          val (x, s) = seed.long
          val i = (x & 0x7fffffff).toInt % n
          if (i < n) buf(i) = t
          seed = s
        }
      }
      r(Some(buf), seed)
    }
  }

  /** A generator that picks a given number of elements from a list, randomly */
  def pick[T](n: Int, g1: Gen[T], g2: Gen[T], gn: Gen[T]*): Gen[Seq[T]] = {
    val gs = g1 +: g2 +: gn
    pick(n, 0 until gs.size).flatMap(idxs =>
      sequence[List[T],T](idxs.toList.map(gs(_)))
    ).suchThat(_.forall(x => gs.exists(_.sieveCopy(x))))
  }

  /** Takes a function and returns a generator that generates arbitrary
   *  results of that function by feeding it with arbitrarily generated input
   *  parameters. */
  def resultOf[T,R](f: T => R)(implicit a: Arbitrary[T]): Gen[R] =
    arbitrary[T] map f

  /** Creates a Function0 generator. */
  def function0[A](g: Gen[A]): Gen[() => A] =
    g.map(a => () => a)


  //// Character Generators ////

  /** Generates a numerical character */
  def numChar: Gen[Char] = choose(48.toChar, 57.toChar)

  /** Generates an upper-case alpha character */
  def alphaUpperChar: Gen[Char] = choose(65.toChar, 90.toChar)

  /** Generates a lower-case alpha character */
  def alphaLowerChar: Gen[Char] = choose(97.toChar, 122.toChar)

  /** Generates an alpha character */
  def alphaChar = frequency((1,alphaUpperChar), (9,alphaLowerChar))

  /** Generates an alphanumerical character */
  def alphaNumChar = frequency((1,numChar), (9,alphaChar))


  //// String Generators ////

  /** Generates a string that starts with a lower-case alpha character,
   *  and only contains alphanumerical characters */
  def identifier: Gen[String] = (for {
    c <- alphaLowerChar
    cs <- listOf(alphaNumChar)
  } yield (c::cs).mkString)

  /** Generates a string of digits */
  def numStr: Gen[String] =
    listOf(numChar).map(_.mkString)

  /** Generates a string of upper-case alpha characters */
  def alphaUpperStr: Gen[String] =
    listOf(alphaUpperChar).map(_.mkString)

  /** Generates a string of lower-case alpha characters */
  def alphaLowerStr: Gen[String] =
      listOf(alphaLowerChar).map(_.mkString)

  /** Generates a string of alpha characters */
  def alphaStr: Gen[String] =
    listOf(alphaChar).map(_.mkString)

  /** Generates a string of alphanumerical characters */
  def alphaNumStr: Gen[String] =
    listOf(alphaNumChar).map(_.mkString)


  //// Number Generators ////

  /** Generates positive numbers of uniform distribution, with an
   *  upper bound of the generation size parameter. */
  def posNum[T](implicit num: Numeric[T], c: Choose[T]): Gen[T] = {
    import num._
    sized(n => c.choose(one, max(fromInt(n), one)))
  }

  /** Generates negative numbers of uniform distribution, with an
   *  lower bound of the negated generation size parameter. */
  def negNum[T](implicit num: Numeric[T], c: Choose[T]): Gen[T] = {
    import num._
    sized(n => c.choose(min(-fromInt(n), -one), -one))
  }

  /** Generates numbers within the given inclusive range, with
   *  extra weight on zero, +/- unity, both extremities, and any special
   *  numbers provided. The special numbers must lie within the given range,
   *  otherwise they won't be included. */
  def chooseNum[T](minT: T, maxT: T, specials: T*)(
    implicit num: Numeric[T], c: Choose[T]
  ): Gen[T] = {
    import num._
    val basics = List(minT, maxT, zero, one, -one)
    val basicsAndSpecials = for {
      t <- specials ++ basics if t >= minT && t <= maxT
    } yield (1, const(t))
    val other = (basicsAndSpecials.length, c.choose(minT, maxT))
    val allGens = basicsAndSpecials :+ other
    frequency(allGens: _*)
  }


  //// Misc Generators ////

  /** Generates a version 4 (random) UUID. */
  lazy val uuid: Gen[java.util.UUID] = for {
    l1 <- Gen.choose(Long.MinValue, Long.MaxValue)
    l2 <- Gen.choose(Long.MinValue, Long.MaxValue)
    y <- Gen.oneOf('8', '9', 'a', 'b')
  } yield java.util.UUID.fromString(
    new java.util.UUID(l1,l2).toString.updated(14, '4').updated(19, y)
  )

  lazy val calendar: Gen[java.util.Calendar] = {
    import java.util.{Calendar, Date}

    val MinYearForCalendar = {
      val c = Calendar.getInstance()
      c.getGreatestMinimum(Calendar.YEAR)
    }

    val MaxYearForCalendar = {
      val c = Calendar.getInstance()
      c.getLeastMaximum(Calendar.YEAR)
    }

    def buildLastDayOfMonth(c: Calendar): Calendar = {
      val lastDayOfMonth = c.getActualMaximum(Calendar.DAY_OF_MONTH)
      c.set(Calendar.DAY_OF_MONTH, lastDayOfMonth)
      c
    }

    def buildFirstDayOfMonth(c: Calendar): Calendar = {
      c.set(Calendar.DAY_OF_MONTH, 1)
      c
    }

    def buildFirstDayOfYear(year: Int): Calendar = {
      val c = Calendar.getInstance()
      c.set(year, 0, 1)
      c
    }

    def buildLastDayOfYear(year: Int): Calendar = {
      val c = Calendar.getInstance()
      c.set(year, 11, 31)
      c
    }

    def buildNearestLeapDate(year: Int): Calendar = {
      val c = Calendar.getInstance()
      c.set(closestLeapYear(year), 1, 29)
      c
    }

    def closestLeapYear(year: Int): Int = {
      var currentYear = year match {
        case y if (y + 4) > MaxYearForCalendar => MaxYearForCalendar - 5
        case _ => year
      }
      while (!isLeapYear(currentYear)) {
        currentYear += 1
      }
      currentYear
    }

    def isLeapYear(year: Int): Boolean = {
      val cal = Calendar.getInstance()
      cal.set(Calendar.YEAR, year)
      cal.getActualMaximum(Calendar.DAY_OF_YEAR) > 365
    }

    val yearGen = Gen.chooseNum(MinYearForCalendar, MaxYearForCalendar)

    val basicCalendarGen: Gen[Calendar] = for {
      l <- Gen.chooseNum(Long.MinValue, Long.MaxValue)
      now = new Date
      d = new Date(now.getTime + l)
      c = Calendar.getInstance()
    } yield {
      c.setTimeInMillis(d.getTime)
      c
    }

    val calendarBeginningOfDayGen: Gen[Calendar] = for {
      c <- basicCalendarGen
    } yield {
      c.set(Calendar.HOUR_OF_DAY, 0)
      c.set(Calendar.MINUTE, 0)
      c.set(Calendar.SECOND, 0)
      c.set(Calendar.MILLISECOND, 0)
      c
    }

    val calendarEndOfDayGen: Gen[Calendar] = for {
      c <- basicCalendarGen
    } yield {
      c.set(Calendar.HOUR_OF_DAY, 23)
      c.set(Calendar.MINUTE, 59)
      c.set(Calendar.SECOND, 59)
      c.set(Calendar.MILLISECOND, 59)
      c
    }

    val firstDayOfYearGen = (1, yearGen.map(buildFirstDayOfYear))
    val lastDayOfYearGen = (1, yearGen.map(buildLastDayOfYear))
    val closestLeapDateGen = (1,yearGen.map(buildNearestLeapDate))
    val beginningOfDayGen = (1, calendarBeginningOfDayGen)
    val endOfDayGen = (1, calendarEndOfDayGen)
    val lastDayOfMonthGen = (1, basicCalendarGen.map(buildLastDayOfMonth))
    val firstDayOfMonthGen = (1, basicCalendarGen.map(buildFirstDayOfMonth))
    val basicsAndSpecials = Seq(
      firstDayOfYearGen,
      lastDayOfYearGen,
      closestLeapDateGen,
      beginningOfDayGen,
      endOfDayGen,
      lastDayOfMonthGen,
      firstDayOfMonthGen
    )
    val allWithFreqs = basicsAndSpecials :+ (basicsAndSpecials.length, basicCalendarGen)

    Gen.frequency(allWithFreqs:_*)
  }

}

/* Copyright (C) 2008-2010 University of Massachusetts Amherst,
   Department of Computer Science.
   This file is part of "FACTORIE" (Factor graphs, Imperative, Extensible)
   http://factorie.cs.umass.edu, http://code.google.com/p/factorie/
   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at
    http://www.apache.org/licenses/LICENSE-2.0
   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License. */

package cc.factorie.la
import cc.factorie._

/** A Vector that may contain arbitrary Double values, represented internally as an Array[Double].
    @author Andrew McCallum */
class DenseVector(val length:Int) extends Vector {
  private val a = new Array[Double](length)
  def activeDomainSize = a.size
  def activeDomain = new Range(0, length, 1)
  override def forActiveDomain(f: (Int)=>Unit): Unit = forIndex(length)(f(_))
  def apply(index:Int): Double = a(index)
  override def update(index:Int, value:Double): Unit = a(index) = value
  override def increment(index:Int, incr:Double): Unit = a(index) += incr
  def set(value:Double): Unit = java.util.Arrays.fill(a, value)
  def activeElements = new Iterator[(Int,Double)] {
    var i = -1
    def hasNext = i < DenseVector.this.length - 1
    def next = { i += 1; (i, a(i)) }
  }
  def dot(v:Vector): Double = v match {
    case v:DenseVector => {
      var result = 0.0
      var i = 0
      while (i < a.size) { result += a(i)*v(i); i += 1 }
      result
    }
    case _ => v dot this
  }
  def addTo(v: Vector,  s: Double) {
    for ((index,value) <- v.activeElements) a(index) += s*value
  }
  def addTo1(v: DenseVector, s: Double) {
    var i = 0
    assert(a.length == v.length, "Cannot add vectors of different lengths")
    while (i < a.length) {
      a(i) += v(i)*s
      i += 1
    }
  }
  def toArray = a
  override def +=(vector:Vector): Unit = {
    vector match {
      case v : DenseVector => {
        assert(v.length == this.length, "Cannot add vectors of different lengths")
        var i = 0
        val vv = v.toArray
        while (i < a.length) {
          a(i) += vv(i)
          i += 1
        }
      }
      case v : VectorTimesScalar => {
        v.vector match {
          case vec : DenseVector => addTo1(vec, v.scalar)
          case vec : Vector => addTo(vec, v.scalar)
        }
      }
      case _ => for ((index,value) <- vector.activeElements) a(index) += value
    }
  }
  override def +=(s:Double): Unit = {
    var i = 0
    while (i < a.size) { a(i) += s; i += 1 }
  }
}

/** Provides a convenient constructor for DenseVector objects.
    @author Andrew McCallum */
object DenseVector {
  def apply(size:Int)(default:Double) = { 
    val result = new DenseVector(size)
    if(default != 0.0) result.set(default)
    result
  }
  def apply(values:Seq[Double]) = {
    val result = new DenseVector(values.length)
    (0 until values.length).map(i => result(i) = values(i))
    result
  }
}
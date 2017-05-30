/*
 * Copyright 2017 Spotify AB.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package com.spotify.featran

import com.spotify.featran.transformers.Settings

import scala.language.{higherKinds, implicitConversions}
import scala.reflect.ClassTag

trait CollectionType[M[_]] { self =>

  def map[A, B: ClassTag](ma: M[A], f: A => B): M[B]
  def reduce[A](ma: M[A], f: (A, A) => A): M[A]
  def cross[A, B: ClassTag](ma: M[A], mb: M[B]): M[(A, B)]

  class MOps[A](ma: M[A]) {
    def map[B: ClassTag](f: A => B): M[B] = self.map(ma, f)
    def reduce(f: (A, A) => A): M[A] = self.reduce(ma, f)
    def cross[B: ClassTag](mb: M[B]): M[(A, B)] = self.cross(ma, mb)
  }

  object Ops {
    implicit def mkMOps[A](xs: M[A]): MOps[A] = new MOps[A](xs)
  }

}

trait FeatureBuilder[T] extends Serializable { self =>
  def init(dimension: Int): Unit
  def add(value: Double): Unit
  def skip(): Unit
  def result: T

  def add(in: Array[Double]): Unit = {
    var i = 0
    while (i < in.length) {
      add(in(i))
      i += 1
    }
  }

  def skip(n: Int): Unit = {
    var i = 0
    while (i < n) {
      skip()
      i += 1
    }
  }

  def map[U](f: T => U): FeatureBuilder[U] = new FeatureBuilder[U] {
    private val delegate = self
    private val g = f
    override def init(dimension: Int): Unit = delegate.init(dimension)
    override def add(value: Double): Unit = delegate.add(value)
    override def skip(): Unit = delegate.skip()
    override def result: U = g(delegate.result)
  }
}

class FeatureExtractor[M[_]: CollectionType, T] private[featran]
(private val fs: FeatureSet[T],
 @transient private val input: M[T],
 @transient private val settings: Option[M[String]])
  extends Serializable {

  import FeatureSpec.ARRAY

  @transient val dt: CollectionType[M] = implicitly[CollectionType[M]]
  import dt.Ops._

  @transient private lazy val as: M[ARRAY] = input.map(fs.unsafeGet)
  @transient private lazy val aggregate: M[ARRAY] = settings match {
    case Some(x) => x.map { s =>
      import io.circe.generic.auto._
      import io.circe.parser._
      fs.decodeAggregators(decode[Seq[Settings]](s).right.get)
    }
    case None => as.map(fs.unsafePrepare).reduce(fs.unsafeSum).map(fs.unsafePresent)
  }

  @transient lazy val featureNames: M[Seq[String]] = aggregate.map(fs.featureNames)

  @transient lazy val featureSettings: M[String] = settings match {
    case Some(x) => x
    case None => aggregate.map { a =>
      import io.circe.generic.auto._
      import io.circe.syntax._
      fs.featureSettings(a).asJson.noSpaces
    }
  }

  def featureValues[F: FeatureBuilder : ClassTag]: M[F] = {
    val fb = implicitly[FeatureBuilder[F]]
    as.cross(aggregate).map { case (a, c) =>
      fs.featureValues(a, c, fb)
      fb.result
    }
  }

}

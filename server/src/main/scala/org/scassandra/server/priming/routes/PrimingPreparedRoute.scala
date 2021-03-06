/*
 * Copyright (C) 2014 Christopher Batey and Dogan Narinc
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.scassandra.server.priming.routes

import org.scassandra.server.priming._
import org.scassandra.server.priming.json.WriteTimeout
import spray.routing.HttpService
import com.typesafe.scalalogging.LazyLogging
import spray.http.StatusCodes
import org.scassandra.server.priming.json._
import org.scassandra.server.priming.prepared._
import scala.collection.immutable.Iterable

trait PrimingPreparedRoute extends HttpService with LazyLogging {

  import PrimingJsonImplicits._

  implicit val primePreparedStore : PrimePreparedStore
  implicit val primePreparedPatternStore : PrimePreparedPatternStore

  val routeForPreparedPriming =
    path("prime-prepared-single") {
      post {
        entity(as[PrimePreparedSingle]) { prime =>
          complete {

            val storeToUse = if (prime.when.query.isDefined && prime.when.queryPattern.isEmpty) {
              Some(primePreparedStore)
            } else if (prime.when.queryPattern.isDefined && prime.when.query.isEmpty) {
              Some(primePreparedPatternStore)
            } else {
              None
            }

            storeToUse match {
              case Some(store) => store.record(prime) match {
                case cp: ConflictingPrimes => StatusCodes.BadRequest -> cp
                case tm: TypeMismatches => StatusCodes.BadRequest -> tm
                case _ => StatusCodes.OK
              }
              case None =>
                StatusCodes.BadRequest -> "Must specify either query or queryPattern, not both"
            }
          }
        }
      } ~
      delete {
        complete {
          primePreparedStore.clear()
          primePreparedPatternStore.clear()
          StatusCodes.OK
        }
      } ~
      get {
        complete {
          val preparedPrimes: Iterable[PrimePreparedSingle] = primePreparedStore.retrievePrimes().map({case (primeCriteria, preparedPrime) =>
            val result = preparedPrime.prime.result match {
              case SuccessResult => Success
              case _: ReadRequestTimeoutResult => ReadTimeout
              case _: WriteRequestTimeoutResult => WriteTimeout
              case _: UnavailableResult => Unavailable
              case _: ServerErrorResult => ServerError
              case _: ProtocolErrorResult => ProtocolError
              case _: BadCredentialsResult => BadCredentials
              case _: OverloadedResult => Overloaded
              case _: IsBootstrappingResult => IsBootstrapping
              case _: TruncateErrorResult => TruncateError
              case _: SyntaxErrorResult => SyntaxError
              case _: UnauthorizedResult => Unauthorized
              case _: InvalidResult => Invalid
              case _: ConfigErrorResult => ConfigError
              case _: AlreadyExistsResult => AlreadyExists
              case _: UnpreparedResult => Unprepared
            }
            PrimePreparedSingle(
              WhenPreparedSingle(
                query = Some(primeCriteria.query), consistency = Some(primeCriteria.consistency)),
              ThenPreparedSingle(
                Some(preparedPrime.prime.rows),
                Some(preparedPrime.variableTypes),
                Some(preparedPrime.prime.columnTypes),
                Some(result)
              )
            )
          })
          preparedPrimes
        }
      }
    }
}

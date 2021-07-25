/*
 * Copyright 2020 University of California, Riverside
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.spark.beast

import org.apache.spark.beast.sql._
import org.apache.spark.sql.types.UDTRegistration
import org.locationtech.jts.geom.Geometry

object SparkSQLRegistration {
  def registerUDT: Unit = {
    // Register the Geometry user-defined data type
    UDTRegistration.register(classOf[Geometry].getName, classOf[GeometryUDT].getName)
  }
}

/*
 * Copyright 2018-2019 ABSA Group Limited
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package za.co.absa.atum.location

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import za.co.absa.atum.location.S3Location.StringS3LocationExt

class S3LocationSpec extends AnyFlatSpec with Matchers {

  val validPathsWithExpectedLocations = Seq(
    // (path, expected parsed value)
    ("s3://mybucket-123/path/to/file.ext", SimpleS3Location("s3", "mybucket-123", "path/to/file.ext")),
    ("s3n://mybucket-123/path/to/ends/with/slash/", SimpleS3Location("s3n","mybucket-123", "path/to/ends/with/slash/")),
    ("s3a://mybucket-123.asdf.cz/path-to-$_file!@#$.ext", SimpleS3Location("s3a", "mybucket-123.asdf.cz", "path-to-$_file!@#$.ext"))
  )

  val invalidPaths = Seq(
    "s3x://mybucket-123/path/to/file/on/invalid/prefix",
    "s3://bb/some/path/but/bucketname/too/short"
  )

  "S3Utils.StringS3LocationExt" should "parse S3 path from String using toS3Location" in {
    validPathsWithExpectedLocations.foreach { case (path, expectedLocation) =>
      path.toS3Location shouldBe Some(expectedLocation)
    }
  }

  it should "find no valid S3 path when parsing invalid S3 path from String using toS3Location" in {
    invalidPaths.foreach {
      _.toS3Location shouldBe None
    }
  }

  it should "fail parsing invalid S3 path from String using toS3LocationOrFail" in {
    invalidPaths.foreach { path =>
      assertThrows[IllegalArgumentException] {
        path.toS3LocationOrFail
      }
    }
  }

  it should "check path using isValidS3Path" in {
    validPathsWithExpectedLocations.map(_._1).foreach { path =>
      path.isValidS3Path shouldBe true
    }

    invalidPaths.foreach(_.isValidS3Path shouldBe false)
  }

}

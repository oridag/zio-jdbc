package zio.jdbc

import zio.test._
import zio.test.Assertion._

object IsSqlFragmentSpec extends ZIOSpecDefault {
  def spec: ZSpec[Environment with TestEnvironment, Any] =
    suite("IsSqlFragment") {
      test("Mapped fragments that are 'complete' compile") {
        val result = typeCheck {
          """
            import zio.jdbc._

            val id = 123
            val fragment1 = sql"select name, age from users"
            val fragment2 = sql"where id = ${id}"

            (fragment1 ++ fragment2).map(_ => "some constant")
          """
        }

        assertM(result)(isRight(isUnit))
      } +
        test("Mapped fragments that are combined with other fragments fail to compile") {
          val result = typeCheck {
            """
            import zio.jdbc._

            val id = 123
            val fragment1 = sql"select name, age from users"
            val fragment2 = sql"where id = ${id}"

            fragment1.map(_ => "some constant") ++ fragment2
          """
          }

          assertM(result)(isLeft(startsWithString("This method can only be invoked on a fragment of SQL")))
        }
    }
}

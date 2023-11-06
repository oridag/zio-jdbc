/*
 * Copyright 2022 John A. De Goes and the ZIO Contributors
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
package zio.jdbc

import zio._

import java.io.File
import java.sql.Connection

/**
 * A `ZConnectionPool` represents a pool of connections, and has the ability to
 * supply a transaction that can be used for executing SQL statements.
 */
abstract class ZConnectionPool {
  def transaction: ZLayer[Any, Throwable, ZConnection]
  def invalidate(conn: ZConnection): UIO[Any]
}

object ZConnectionPool {
  def h2test: ZLayer[Any, Throwable, ZConnectionPool] =
    ZLayer.scoped {
      for {
        _      <- ZIO.attempt(Class.forName("org.h2.Driver"))
        int    <- Random.nextInt
        acquire = ZIO.attemptBlocking {
                    java.sql.DriverManager.getConnection(s"jdbc:h2:mem:test_database_$int")
                  }
        zenv   <- make(acquire).build.provideSome[Scope](ZLayer.succeed(ZConnectionPoolConfig.default))
      } yield zenv.get[ZConnectionPool]
    }

  def h2mem(
    database: String,
    props: Map[String, String] = Map()
  ): ZLayer[ZConnectionPoolConfig, Throwable, ZConnectionPool] =
    ZLayer.scoped {
      for {
        _      <- ZIO.attempt(Class.forName("org.h2.Driver"))
        acquire = ZIO.attemptBlocking {
                    val properties = new java.util.Properties
                    props.foreach { case (k, v) => properties.setProperty(k, v) }

                    java.sql.DriverManager.getConnection(s"jdbc:h2:mem:$database", properties)
                  }
        zenv   <- make(acquire).build
      } yield zenv.get[ZConnectionPool]
    }

  def h2file(
    directory: File,
    database: String,
    props: Map[String, String] = Map()
  ): ZLayer[ZConnectionPoolConfig, Throwable, ZConnectionPool] =
    ZLayer.scoped {
      for {
        _      <- ZIO.attempt(Class.forName("org.h2.Driver"))
        acquire = ZIO.attemptBlocking {
                    val properties = new java.util.Properties
                    props.foreach { case (k, v) => properties.setProperty(k, v) }

                    java.sql.DriverManager.getConnection(s"jdbc:h2:file:$directory/$database", properties)
                  }
        zenv   <- make(acquire).build
      } yield zenv.get[ZConnectionPool]
    }

  def oracle(
    host: String,
    port: Int,
    database: String,
    props: Map[String, String]
  ): ZLayer[ZConnectionPoolConfig, Throwable, ZConnectionPool] =
    ZLayer.scoped {
      for {
        _      <- ZIO.attempt(Class.forName("oracle.jdbc.OracleDriver"))
        acquire = ZIO.attemptBlocking {
                    val properties = new java.util.Properties
                    props.foreach { case (k, v) => properties.setProperty(k, v) }

                    java.sql.DriverManager.getConnection(s"jdbc:oracle:thin:@$host:$port:$database", properties)
                  }
        zenv   <- make(acquire).build
      } yield zenv.get[ZConnectionPool]
    }

  def postgres(
    host: String,
    port: Int,
    database: String,
    props: Map[String, String]
  ): ZLayer[ZConnectionPoolConfig, Throwable, ZConnectionPool] =
    ZLayer.scoped {
      for {
        _      <- ZIO.attempt(Class.forName("org.postgresql.Driver"))
        acquire = ZIO.attemptBlocking {
                    val properties = new java.util.Properties

                    props.foreach { case (k, v) => properties.setProperty(k, v) }

                    java.sql.DriverManager.getConnection(s"jdbc:postgresql://$host:$port/$database", properties)
                  }
        zenv   <- make(acquire).build
      } yield zenv.get[ZConnectionPool]
    }

  def sqlserver(
    host: String,
    port: Int,
    database: String,
    props: Map[String, String]
  ): ZLayer[ZConnectionPoolConfig, Throwable, ZConnectionPool] =
    ZLayer.scoped {
      for {
        _      <- ZIO.attempt(Class.forName("com.microsoft.sqlserver.jdbc.SQLServerDriver"))
        acquire = ZIO.attemptBlocking {
                    val properties = new java.util.Properties

                    props.foreach { case (k, v) => properties.setProperty(k, v) }

                    java.sql.DriverManager
                      .getConnection(s"jdbc:sqlserver://$host:$port;databaseName=$database", properties)
                  }
        zenv   <- make(acquire).build
      } yield zenv.get[ZConnectionPool]
    }

  def mysql(
    host: String,
    port: Int,
    database: String,
    props: Map[String, String]
  ): ZLayer[ZConnectionPoolConfig, Throwable, ZConnectionPool] =
    ZLayer.scoped {
      for {
        _      <- ZIO.attempt(Class.forName("com.mysql.cj.jdbc.Driver"))
        acquire = ZIO.attemptBlocking {
                    val properties = new java.util.Properties
                    props.foreach { case (k, v) => properties.setProperty(k, v) }

                    java.sql.DriverManager
                      .getConnection(s"jdbc:mysql://$host:$port/$database", properties)
                  }
        zenv   <- make(acquire).build
      } yield zenv.get[ZConnectionPool]
    }

  def make(acquire: Task[Connection]): ZLayer[ZConnectionPoolConfig, Throwable, ZConnectionPool] =
    ZLayer.scoped {
      for {
        config <- ZIO.service[ZConnectionPoolConfig]
        getConn = ZIO.acquireRelease(acquire.retry(config.retryPolicy).flatMap(ZConnection.make))(_.close.ignoreLogged)
        pool   <- ZPool.make(getConn, Range(config.minConnections, config.maxConnections), config.timeToLive)
        tx      = ZLayer.scoped {
                    for {
                      connection <- pool.get
                      _          <- ZIO.addFinalizerExit { exit =>
                                      ZIO
                                        .ifZIO(connection.isValid().orElse(ZIO.succeed(false)))(
                                          onTrue = exit match {
                                            case Exit.Success(_) => connection.restore
                                            case Exit.Failure(_) =>
                                              for {
                                                autoCommitMode <- connection.access(_.getAutoCommit).orElseSucceed(true)
                                                _              <- ZIO.unless(autoCommitMode)(connection.rollback.ignoreLogged)
                                                _              <- connection.restore
                                              } yield ()
                                          },
                                          onFalse = pool.invalidate(connection)
                                        )
                                    }
                    } yield connection
                  }
      } yield new ZConnectionPool {
        def transaction: ZLayer[Any, Throwable, ZConnection] = tx
        def invalidate(conn: ZConnection): UIO[Any]          = pool.invalidate(conn)
      }
    }
}

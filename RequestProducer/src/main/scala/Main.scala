import zio._
import zio.kafka.consumer._
import zio.kafka.producer.{Producer, ProducerSettings}
import zio.kafka.serde._
import zio.http._
import zio.http.model.Method
import zio.stream.ZStream
import zio.json._
import org.apache.avro.Schema
import org.apache.avro.generic.GenericRecord
import org.apache.avro.generic.GenericData
import io.confluent.kafka.serializers.KafkaAvroSerializer
import io.confluent.kafka.serializers.KafkaAvroDeserializer
import collection.JavaConverters.mapAsJavaMapConverter
import org.apache.avro.Schema
import org.apache.avro.specific.SpecificRecordBase
import io.confluent.kafka.serializers.AbstractKafkaAvroSerDeConfig


object MainApp extends ZIOAppDefault:
  case class RequestModel(id: Long) 

  object RequestModel {
    implicit val decoder: JsonDecoder[RequestModel] = DeriveJsonDecoder.gen[RequestModel]
    implicit val encoder: JsonEncoder[RequestModel] = DeriveJsonEncoder.gen[RequestModel]
  }

  val avroSerializer = new KafkaAvroSerializer()
  avroSerializer.configure(Map("schema.registry.url" -> "http://schema-registry:8081", "specific.avro.reader" -> true).asJava, false)

  def producer(request: RequestModel) =
    Producer.produce[Any, Long, Array[Byte]](
      topic = "request-created",
      key = request.id,
      value = avroSerializer.serialize("request-created", RequestAvroModel(request.id)),
      keySerializer = Serde.long,
      valueSerializer = Serde.byteArray
    )
      .catchAll(e => Console.printLineError(e.getMessage()))
      .ignore

  def producerLayer(config: KafkaConfig) =
    ZLayer.scoped(
      Producer.make(
        settings = ProducerSettings(List(config.hostname))
          .withProperties(Map(
            AbstractKafkaAvroSerDeConfig.SCHEMA_REGISTRY_URL_CONFIG -> "http://schema-registry:8081"
          ))
      )
    )

  val app: HttpApp[Producer, Nothing] = Http.collectZIO[Request] {
    case req@Method.POST  -> !! / "request"  => {
      req.body.asString
        .map(_.fromJson[RequestModel])
        .flatMap(_ match {
          case Right(request) => producer(request).map(_ => Response.text("That's a valid request!"))
          case Left(value)    => ZIO.succeed(Response.text(s"That's some junk! $value"))
        })
        .catchAll(e => ZIO.succeed(Response.text("Something went wrong")))
    }
  }

  final case class KafkaConfig(username: String, hostname: String, password: String)
  object KafkaConfig:
    val config: Config[KafkaConfig] =
      (Config.string("USERNAME") ++ Config.string("HOSTNAME") ++ Config.string("PASSWORD")).map {
        case (username, hostname, password) => KafkaConfig(username, hostname, password)
      }

  override def run =
    for {
      config <- ZIO.config(KafkaConfig.config).withConfigProvider(ConfigProvider.envProvider.upperCase)
      _ <- Console.printLine(s"Hostname is: " ++ config.hostname)
      _ <- Server.serve(app).provide(producerLayer(config), Server.defaultWithPort(8090))
    } yield ()
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
import io.confluent.kafka.serializers.AbstractKafkaAvroSerDeConfig

object MainApp extends ZIOAppDefault:
  val KAFKA_SERVERS = List("kafka:29092")
  val KAFKA_PROPERTIES : Map[String, String] = Map(
    AbstractKafkaAvroSerDeConfig.SCHEMA_REGISTRY_URL_CONFIG -> "http://schema-registry:8081"
  )

  val avroDeserializer = new KafkaAvroDeserializer()
  avroDeserializer.configure(Map("schema.registry.url" -> "http://schema-registry:8081", "specific.avro.reader" -> true).asJava, false)

  val consumer: ZStream[Consumer, Throwable, Nothing] =
    Consumer
      .plainStream(Subscription.topics("request-created"), Serde.long, Serde.byteArray)
      .tap(request => {
        val result = avroDeserializer.deserialize("request-created", request.value).asInstanceOf[RequestAvroModel] 
        val id = result.getId
        Console.printLine(s"Got Result $id")
      })
      .map(_.offset)
      .aggregateAsync(Consumer.offsetBatches)
      .mapZIO(_.commit)
      .drain

  def consumerLayer(config: KafkaConfig) =
    ZLayer.scoped(
      Consumer.make(
        ConsumerSettings(List(config.hostname))
          .withProperties(KAFKA_PROPERTIES)
          .withGroupId("group")
      )
    )

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
      _ <- consumer
        .runDrain
        .provide(consumerLayer(config))
    } yield ()
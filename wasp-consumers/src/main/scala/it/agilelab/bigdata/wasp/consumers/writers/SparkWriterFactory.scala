package it.agilelab.bigdata.wasp.consumers.writers

import com.typesafe.config.{Config, ConfigFactory}
import it.agilelab.bigdata.wasp.core.bl.{IndexBL, RawBL, TopicBL}
import it.agilelab.bigdata.wasp.core.logging.WaspLogger
import it.agilelab.bigdata.wasp.core.models.WriterModel
import org.apache.spark.SparkContext
import org.apache.spark.streaming.StreamingContext

trait SparkWriterFactory {
  def createSparkWriterStreaming(env: {val indexBL: IndexBL; val topicBL: TopicBL; val rawBL: RawBL}, ssc: StreamingContext, writerModel: WriterModel): Option[SparkStreamingWriter]
  def createSparkWriterBatch(env: {val indexBL: IndexBL; val rawBL: RawBL}, sc: SparkContext, writerModel: WriterModel): Option[SparkWriter]
}

object SparkWriterFactoryDefault extends SparkWriterFactory {
  val logger = WaspLogger(this.getClass.getName)
  val conf: Config = ConfigFactory.load
  val defaultDataStoreIndexed = conf.getString("default.datastore.indexed")

  override def createSparkWriterStreaming(env: {val topicBL: TopicBL; val indexBL: IndexBL; val rawBL: RawBL}, ssc: StreamingContext, writerModel: WriterModel): Option[SparkStreamingWriter] = {


    writerModel.writerType.wtype match {
      case "index" => {
        defaultDataStoreIndexed match {
          case "elastic" => Some(new ElasticSparkStreamingWriter(env, ssc, writerModel.id.stringify))
          case "solr" => Some(new SolrSparkStreamingWriter(env, ssc, writerModel.id.stringify))
          case _ => Some(new ElasticSparkStreamingWriter(env, ssc, writerModel.id.stringify))
        }
      }
      case "topic" => Some(new KafkaSparkStreamingWriter(env, ssc, writerModel.id.stringify))
      case "raw" => RawWriter.createSparkStreamingWriter(env, ssc, writerModel.id.stringify)
      case _ =>
        logger.error(s"Invalid spark streaming writer type, writer model: $writerModel")
        None
    }
  }

  override def createSparkWriterBatch(env: {val indexBL: IndexBL; val rawBL: RawBL}, sc: SparkContext, writerModel: WriterModel): Option[SparkWriter] = {
    writerModel.writerType.wtype match {
      case "index" => {
        defaultDataStoreIndexed match {
          case "elastic" => Some(new ElasticSparkWriter(env, sc, writerModel.id.stringify))
          case "solr" => Some(new SolrSparkWriter(env, sc, writerModel.id.stringify))
          case _ =>  Some(new ElasticSparkWriter(env, sc, writerModel.id.stringify))
        }
      }
      case "raw" => RawWriter.createSparkWriter(env, sc, writerModel.id.stringify)
      case _ =>
        logger.error(s"Invalid spark writer type, writer model: $writerModel")
        None
    }
  }
}
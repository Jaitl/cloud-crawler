package com.github.jaitl.crawler.worker.pipeline

import com.github.jaitl.crawler.worker.parser.NoParser
import com.github.jaitl.crawler.worker.timeout.RandomTimeout

private[pipeline] class ConfigurablePipelineBuilder[T] {
  private var batchSize: Option[Int] = None
  private var resourceType: Option[ResourceType] = None

  def withBatchSize(batchSize: Int): this.type = {
    this.batchSize = Some(batchSize)
    this
  }

  def withProxy(host: String, post: Int, limit: Int, timeout: RandomTimeout): this.type = {
    resourceType = Some(Proxy(host, post, limit, timeout))
    this
  }

  def withProxy(
    host: String,
    post: Int,
    limit: Int,
    timeout: RandomTimeout,
    login: String,
    password: String): this.type = {
    resourceType = Some(Proxy(host, post, limit, timeout, login, password))
    this
  }

  def withTor(
    host: String,
    post: Int,
    limit: Int,
    timeout: RandomTimeout,
    controlPort: Int,
    password: String): this.type = {
    resourceType = Some(Tor(host, post, limit, timeout, controlPort, password))
    this
  }

  def build(): ConfigurablePipeline[T] = {

    if (batchSize.isEmpty) {
      throw new PipelineBuilderException("batch size is not defined")
    }
    if (resourceType.isEmpty) {
      throw new PipelineBuilderException("proxy or tor is not defined")
    }

    ConfigurablePipeline(
      batchSize = batchSize.get,
      resourceType = resourceType.get
    )
  }
}

object ConfigurablePipelineBuilder {
  def apply[T](): ConfigurablePipelineBuilder[T] = new ConfigurablePipelineBuilder[T]()
  def noParserPipeline(): ConfigurablePipelineBuilder[NoParser] = new ConfigurablePipelineBuilder[NoParser]()
}

class PipelineBuilderException(message: String) extends Exception(message)

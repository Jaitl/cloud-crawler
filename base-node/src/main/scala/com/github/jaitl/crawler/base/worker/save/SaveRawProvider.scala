package com.github.jaitl.crawler.base.worker.save

import com.github.jaitl.crawler.base.worker.crawler.CrawlResult
import com.github.jaitl.crawler.base.worker.crawler.CrawlTask

import scala.concurrent.Future

trait SaveRawProvider {
  def save(raw: Seq[(CrawlTask, CrawlResult)]): Future[Unit]
}

package com.github.jaitl.cloud.simple.worker.parser


import java.text.SimpleDateFormat

import com.github.jaitl.crawler.worker.crawler.CrawlResult
import com.github.jaitl.crawler.worker.crawler.CrawlTask
import com.github.jaitl.crawler.worker.parser.BaseParser
import com.github.jaitl.crawler.worker.parser.ParseResult
import org.jsoup.Jsoup

import scala.collection.JavaConverters._

//scalastyle:off
class StackoverflowParser extends BaseParser[StackowerflowParsedData] {
  val dateFormat = new SimpleDateFormat("yyyy-mm-dd HH:mm:ss")

  override def parse(crawlTask: CrawlTask, crawlResult: CrawlResult): ParseResult[StackowerflowParsedData] = {
    val doc = Jsoup.parse(crawlResult.data)

    val date = dateFormat.parse(doc.select("div.user-action-time span")
      .attr("title").replace("Z", "")).getTime
    val title = doc.select("title").text()
    val content = doc.select("div.post__body").text()
    val url = doc.select("meta[property=\"og:url\"]").attr("content")
    val id = doc.select("#question").attr("data-questionid").toLong
    val tags = doc.select("div.post-taglist div a").asScala.map {
      el => el.text()
    }

    val comments = doc.select("#answers div.answer").asScala.map {
      el =>
        SatckoverflowComments(
          el.select("div.post-text").html(),
          el.attr("data-answerid").toLong,
          dateFormat.parse(el.select("div.user-action-time span")
            .attr("title").replace("Z", "")
          ).getTime,
          SatckoverflowUser(
            el.select("div.user-details a").attr("href").split("/")(2).toLong,
            el.select("div.user-details a").text(),
            el.select("div.user-details a").attr("href")

          )
        )
    }

    val user = doc.select("div.question div.owner").asScala.map {
      el =>
        SatckoverflowUser(
          el.select("div.user-details a").attr("href").split("/")(2).toLong,
          el.select("div.user-details a").text(),
          el.select("div.user-details a").attr("href")

        )
    }.head


    val hints = doc.select("div.question ul.comments-list li").asScala.map {
      el =>
        SatckoverflowHints(
          el.select("span.comment-copy").html(),
          el.attr("data-comment-id").toLong,
          dateFormat.parse(el.select("span.comment-date span")
            .attr("title").replace("Z", "")
          ).getTime,
          SatckoverflowUser(
            el.select("div.comment-body a.comment-user").attr("href").split("/")(2).toLong,
            el.select("div.comment-body a.comment-user").text(),
            el.select("div.comment-body a.comment-user").attr("href")

          )
        )
    }

    ParseResult(StackowerflowParsedData(title, content, url, id, date, tags, comments, hints, user))
  }
}
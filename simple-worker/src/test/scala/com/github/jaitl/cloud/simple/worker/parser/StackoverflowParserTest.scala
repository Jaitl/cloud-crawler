package com.github.jaitl.cloud.simple.worker.parser

import com.github.jaitl.crawler.worker.crawler.{CrawlResult, CrawlTask}
import org.scalatest.{FunSuite, Matchers}

import scala.io.Source

class StackoverflowParserTest extends FunSuite with Matchers {
  test("Stackoverflow") {
    val content = Source.fromResource("html/so_q.html").mkString
    val parser = new StackoverflowParser

    val res = parser.parse(CrawlTask("1000", "StackTasks"), CrawlResult(content)).parsedData
    res.tags.size shouldBe 2
    res.tags shouldEqual Seq("javascript", "jquery")
    res.url shouldBe "https://stackoverflow.com/questions/54493924/keydown-making-a-loop-to-acces-an-object-for-each-key-pressed/54497761"
    res.date shouldBe 1546454270000L
    res.title shouldBe "javascript - keydown - making a loop to acces an object for each key pressed - Stack Overflow"


    res.user.name shouldBe "The RainMan"
    res.user.id shouldBe 11005437
    res.user.url shouldBe "/users/11005437/the-rainman"

    res.hints.size shouldBe 2
    res.hints.head.user shouldEqual SatckoverflowUser(3874623L, "Mark Meyer", "/users/3874623/mark-meyer")
    res.hints.head.id shouldBe 95793358
    res.hints.head.date shouldBe 1546428410000L
    res.hints.head.body shouldBe "Do you just want to take the values from the <code>keys</code> object? If so, you should make that clear and maybe change the text from <code>aassdd</code> to <code>text1</code> so they agree. Right now it's not clear if this is what you are asking."

    res.comments.size shouldBe 2
    res.comments.head.id shouldBe 54497761
    res.comments.head.user shouldBe SatckoverflowUser(1771994L, "Tom O.", "/users/1771994/tom-o")
    res.comments.head.date shouldBe 1546454682000L
  }
}
package com.github.jaitl.crawler.worker.creator

import akka.actor.ActorRef
import akka.actor.ActorRefFactory

trait TwoArgumentActorCreator[O, T] {
  def create(factory: ActorRefFactory, firstArg: O, secondArg: T): ActorRef
}

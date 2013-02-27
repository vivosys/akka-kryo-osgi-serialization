/*
 * Copyright (c) 2013 VIVO Systems <http://vivosys.com>
 */

package com.vivosys.akka.serializer

import scala._

/**
 * Bundles should extend this trait and expose it as an OSGi micro-service. An example implementation might be:
 *
 * {{{
 * class ClassRegistrationHookImpl extends ClassRegistrationHook {
 *   def startRid() = 1100
 *
 *   def classes() = Seq(
 *     classOf[OneClass],
 *     classOf[TwoClass],
 *     classOf[ThreeClass]
 *   )
 * }
 * }}}
 *
 * Subclasses must define the startRid to avoid overlap with other subclasses.
 */
trait ClassRegistrationHook {

  private var rid = startRid()
  private val registrations: Seq[ClassRegistration] = create()

  def nextRid() = {
    val oldRid = rid; rid = rid + 1; oldRid
  }

  protected def create(): Seq[ClassRegistration] = {
    classes().map(c => ClassRegistration(c, nextRid()))
  }

  def classRegistrations() = registrations

  // override in subclasses
  def startRid(): Int

  // override in subclasses
  def classes(): Seq[Class[_]]

}

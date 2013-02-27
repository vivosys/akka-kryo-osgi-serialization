/*
 * Copyright (c) 2013 VIVO Systems <http://vivosys.com>
 */

package com.vivosys.akka.serializer

import scala.language.existentials

case class ClassRegistration(
  clazz: Class[_],
  id: Int
)

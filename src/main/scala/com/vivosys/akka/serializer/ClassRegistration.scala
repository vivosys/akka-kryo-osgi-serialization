package com.vivosys.akka.serializer

import scala.language.existentials

case class ClassRegistration(
  clazz: Class[_],
  id: Int
)

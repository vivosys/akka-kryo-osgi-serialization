package com.vivosys.akka.serializer

/**
 * A serializer that can accept class registrations at runtime via ClassRegistrationHook.
 */
trait ClassRegistrarSerializer {

  def addRegistrationHook(registrationHook: ClassRegistrationHook, properties: java.util.Map[String, String])

  def removeRegistrationHook(registrationHook: ClassRegistrationHook, properties: java.util.Map[String, String])

}

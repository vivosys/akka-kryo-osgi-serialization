package com.vivosys.akka.serializer

import akka.actor.ActorSystem
import akka.serialization.SerializationExtension

/**
 * Obtains access to the ClassRegistrarSerializer instance created by Akka, and delegates registration hook
 * registration and deregistration to it. It acts as an interface between code that wishes to dynamically register
 * classes for registration/deregistration at runtime via the ClassRegistrationHook, and the serializer that is
 * created internally by Akka.
 *
 * This class assumes that the Akka configuration maps java.io.Serializable to an instance of a
 * ClassRegistrarSerializer.
 *
 * An example use of this would be to create an instance of this class, initialize it, and then inject any OSGi
 * micro-services exposing the ClassRegistrationHook interface into it. It will delegate to the serializer registered
 * in Akka, which must implement the ClassRegistrarSerializer interface (e.g. KryoSerializer).
 */
class ClassRegistrarSerializerAkkaGlue(actorSystem: ActorSystem, initialRegistrations: Array[ClassRegistrationHook]) {

  var serializer: ClassRegistrarSerializer = _

  def init() {
    // get Akka's serialization Extension from which we can obtain the ClassRegistrarSerializer
    val serialization = SerializationExtension(actorSystem)
    serializer = serialization.serializerFor(classOf[Serializable]).asInstanceOf[ClassRegistrarSerializer]

    initialRegistrations.foreach(r => addRegistrationHook(r, null))
  }

  def destroy() {
    initialRegistrations.foreach(r => removeRegistrationHook(r, null))
  }

  def addRegistrationHook(registrationHook: ClassRegistrationHook, properties: java.util.Map[String, String]) {
    serializer.addRegistrationHook(registrationHook, properties)
  }

  def removeRegistrationHook(registrationHook: ClassRegistrationHook, properties: java.util.Map[String, String]) {
    serializer.removeRegistrationHook(registrationHook, properties)
  }

}

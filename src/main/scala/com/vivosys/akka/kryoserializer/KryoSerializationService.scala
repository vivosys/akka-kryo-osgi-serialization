package com.vivosys.akka.kryoserializer

import akka.actor._
import akka.serialization.{Serialization, Serializer}
import com.esotericsoftware.kryo.Kryo
import com.esotericsoftware.kryo.io.{Output, Input}
import com.esotericsoftware.kryo.serializers.DefaultArraySerializers.ByteArraySerializer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.objenesis.strategy.StdInstantiatorStrategy
import org.slf4j.LoggerFactory
import scala.Some
import scala.collection.mutable
import scala.language.existentials
import scala.reflect.ClassTag
import com.vivosys.akka.serializer.{ClassRegistration, ClassRegistrationHook, ClassRegistrarSerializer}

/**
 * A serialization service using Kryo. Most of this logic is based on the akka-kryo-serialization extension module
 * with the addition of the ability to register Class instances that have been injected via a ClassRegistrationHook.
 * This can then support any system where class registrations need to be injected dynamically at runtime, such as
 * via OSGi services. Hooks can also be added programmatically in non-OSGi environments.
 *
 * Kryo requires registered classes to be given integer IDs, and these IDs must match on both the serialization side
 * and the deserialization side.
 *
 * Therefore, we can assign IDs based on ranges i.e. classes registered by exposing ClassRegistrationHook
 * implementations from bundles. Some basic registrations such as java byte[], various collections, Scala Option, etc.
 * are automatic, and have a reserved range. Bundles that expose ClassRegistrationHook should carefully assign their
 * ID range so as not to overlap.
 */
class KryoSerializer(actorSystem: ExtendedActorSystem) extends Serializer with ClassRegistrarSerializer {

  // TODO use Akka logging?
  val log_ = LoggerFactory.getLogger(classOf[KryoSerializer])

  val classRegistrations = mutable.Map[Int, ClassRegistration]()
  val serializerPool = createSerializerPool

  // register fixed registrations at init time
  addRegistrationHook(FixedRegistrations, null)

  def addRegistrationHook(registrationHook: ClassRegistrationHook, properties: java.util.Map[String, String]) {
    registrationHook.classRegistrations().foreach(r => register(r))

    // not thread-safe, we count on all the classes being registered at startup time
    serializerPool.clear()
  }

  def removeRegistrationHook(registrationHook: ClassRegistrationHook, properties: java.util.Map[String, String]) {
    registrationHook.classRegistrations().foreach(r => deregister(r))

    // not thread-safe, we count on all the classes being registered at startup time
    serializerPool.clear()
  }

  def register(registration: ClassRegistration) {
    log_.debug("Loaded registration for class {} with id {}.", registration.clazz.getName, registration.id)
    classRegistrations(registration.id) = registration
  }

  def deregister(registration: ClassRegistration) {
    classRegistrations remove registration.id
    log_.debug("Unloaded registration for class {} with id {}.", registration.clazz.getName, registration.id)
  }

  def identifier = 256

  def createSerializerPool: ObjectPool[Serializer] = {
    new ObjectPool[Serializer](16, ()=> {
      new KryoBasedSerializer(newKryo(), 4096, false)
    })
  }

  // Delegate to a real serializer
  def toBinary(obj: AnyRef): Array[Byte] = {
    val ser = getSerializer
    try {
      ser.toBinary(obj)
    } catch {
      case t: Throwable => {
        log_.error("Cannot serialize class {}.", obj.getClass.getName, t)
        throw t
      }
    } finally {
      releaseSerializer(ser)
    }
  }

  def fromBinary(bytes: Array[Byte], clazz: Option[Class[_]]): AnyRef = {
    val ser = getSerializer
    try {
      ser.fromBinary(bytes, clazz)
    } catch {
      case t: Throwable => {
        clazz match {
          case Some(c) => log_.error("Cannot deserialize class {}.", clazz.get.getName, t)
          case None => log_.error("Cannot deserialize bytes.", t)
        }
        throw t
      }
    } finally {
      releaseSerializer(ser)
    }
  }

  def includeManifest = false

  private def getSerializer = serializerPool.fetch()

  private def releaseSerializer(ser: Serializer) = serializerPool.release(ser)


  /**
   * Use the newKryo method from the KryoSerializer extension, but modified to simplify it (various options removed)
   * and to support the registration of serializers registered as OSGi services.
   * @return
   */
  private def newKryo(): Kryo = {

    val kryo = new Kryo()
    kryo.setClassLoader(getClass.getClassLoader)

    // Support deserialization of classes without no-arg constructors
    kryo.setInstantiatorStrategy(new StdInstantiatorStrategy())

    // require classes to be registered
    kryo.setRegistrationRequired(true)
    kryo.setReferences(false)

    // Support serialization of Scala collections (these are from akka-kryo-serializer, not supported yet on Scala 2.10)
/*
    kryo.addDefaultSerializer(classOf[scala.Enumeration#Value], classOf[EnumerationSerializer])
    kryo.addDefaultSerializer(classOf[scala.collection.Map[_, _]], classOf[ScalaMapSerializer])
    kryo.addDefaultSerializer(classOf[scala.collection.Set[_]], classOf[ScalaSetSerializer])
    kryo.addDefaultSerializer(classOf[scala.collection.generic.MapFactory[scala.collection.Map]],
      classOf[ScalaMapSerializer])
    kryo.addDefaultSerializer(classOf[scala.collection.generic.SetFactory[scala.collection.Set]],
      classOf[ScalaSetSerializer])
    kryo.addDefaultSerializer(classOf[scala.collection.Traversable[_]], classOf[ScalaCollectionSerializer])
*/
    kryo.addDefaultSerializer(classOf[ActorRef], new ActorRefSerializer(actorSystem))

    // register all available classes, including those exposed as OSGi services via a ClassRegistrationHook
    classRegistrations.values.foreach(reg => {
      kryo.register(reg.clazz, reg.id)
    })

    kryo
  }

}

object FixedRegistrations extends ClassRegistrationHook {

  def startRid() = 10

  def classes() = Seq(
    classOf[Array[Byte]],

    classOf[java.util.HashMap[_, _]],
    classOf[java.util.ArrayList[_]],
    classOf[java.util.LinkedList[_]],
    classOf[java.util.Date],

    None.getClass,
    classOf[scala.Some[_]],

    classOf[ActorRef],
    // cannot use classOf, has visibility private[akka]
    Class.forName("akka.actor.RepointableActorRef")
  )

}

/**
 * This code from akka-kryo-serialization (https://github.com/romix/akka-kryo-serialization).
 * @param kryo
 * @param bufferSize
 * @param useManifests
 */
class KryoBasedSerializer(val kryo: Kryo, val bufferSize: Int, val useManifests:Boolean) extends Serializer {

  // This is whether "fromBinary" requires a "clazz" or not
  def includeManifest: Boolean = useManifests

  // A unique identifier for this Serializer
  def identifier = 256

  // "toBinary" serializes the given object to an Array of Bytes
  def toBinary(obj: AnyRef): Array[Byte] = {
    val buffer = getBuffer
    try {
      if (obj == null || ! useManifests) {
        kryo.writeClassAndObject(buffer, obj)
      } else {
        kryo.writeObject(buffer, obj)
      }
      buffer.toBytes
    } finally
      releaseBuffer(buffer)
  }

  // "fromBinary" deserializes the given array,
  // using the type hint (if any, see "includeManifest" above)
  // into the optionally provided classLoader.
  def fromBinary(bytes: Array[Byte], clazz: Option[Class[_]]): AnyRef = {
    if(! useManifests) {
      kryo.readClassAndObject(new Input(bytes))
    } else {
      clazz match {
        case Some(c) => kryo.readObject(new Input(bytes), c).asInstanceOf[AnyRef]
        case _ => throw new RuntimeException("Object of unknown class cannot be deserialized")
      }
    }
  }

  val buf = new Output(bufferSize, 1024 * 1024 * 20)
  private def getBuffer = buf
  private def releaseBuffer(buffer: Output) = { buffer.clear() }

}

class ActorRefSerializer(val system: ExtendedActorSystem) extends com.esotericsoftware.kryo.Serializer[ActorRef]  {

  {
    setAcceptsNull(true)
    setImmutable(true)
  }

  val delegate = new ByteArraySerializer

  override def read(kryo: Kryo, input: Input, typ: Class[ActorRef]): ActorRef = {
    val path = new String(delegate.read(kryo, input, null), "UTF-8")
    system.actorFor(path)
  }

  override def write(kryo: Kryo, output: Output, obj: ActorRef) = {
    if(obj == null) {
      delegate.write(kryo, output, null)
    } else {
      val address = (Serialization.currentTransportAddress.value match {
        case null => obj.path.toString
        case addr => obj.path.toStringWithAddress(addr)
      }).getBytes("UTF-8")
      delegate.write(kryo, output, address)
    }
  }
}

class ObjectPool[T : ClassTag](number: Int, newInstance: ()=>T) {

  private val size = new AtomicInteger(0)
  private val pool = new ArrayBlockingQueue[T](number)

  def fetch(): T = {
    pool.poll() match {
      case null => createOrBlock
      case o: T => o
    }
  }

  def release(o: T) {
    pool.offer(o)
  }

  def add(o: T) {
    pool.add(o)
  }

  def clear() {
    pool.clear()
  }

  private def createOrBlock: T = {
    size.get match {
      case e: Int if e >= number => block
      case _ => create
    }
  }

  private def create: T = {
    size.incrementAndGet match {
      case e: Int if e > number => size.decrementAndGet; fetch()
      case e: Int => newInstance()
    }
  }

  private def block: T = {
    val timeout = 5000
    pool.poll(timeout, TimeUnit.MILLISECONDS) match {
      case null => throw new Exception("Couldn't acquire object in %d milliseconds.".format(timeout))
      case o: T => o
    }
  }
}

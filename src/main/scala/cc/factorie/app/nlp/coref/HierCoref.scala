package cc.factorie.app.nlp.coref
import cc.factorie._
import com.mongodb.DB
import db.mongo._
import collection.mutable.{LinkedHashMap, HashMap, HashSet, ArrayBuffer}

class BagOfTruths(val entity:Entity, truths:Map[String,Double]=null) extends BagOfWordsVariable(Nil,truths) with EntityAttr
class EntityExists(val entity:Entity,initialValue:Boolean) extends BooleanVariable(initialValue)
class IsEntity(val entity:Entity,initialValue:Boolean) extends BooleanVariable(initialValue)
class IsMention(val entity:Entity,initialValue:Boolean) extends BooleanVariable(initialValue)
class Dirty(val entity:Entity) extends IntegerVariable(0){def reset()(implicit d:DiffList):Unit=this.set(0)(d);def ++()(implicit d:DiffList):Unit=this.set(intValue+1)(d);def --()(implicit d:DiffList):Unit=this.set(intValue-1)(d)} //convenient for determining whether an entity needs its attributes recomputed
abstract class HierEntity(isMent:Boolean=false) extends Entity{
  isObserved=isMent
  var groundTruth:Option[String] = None
  val bagOfTruths = new BagOfTruths(this)    
  def flagAsMention:Unit = {isObserved=true;isMention.set(true)(null)}
  def isEntity = attr[IsEntity]
  def isMention = attr[IsMention]
  def exists = attr[EntityExists]
  def dirty = attr[Dirty]
  attr += new EntityExists(this,this.isConnected)
  attr += new IsEntity(this,this.isRoot)
  attr += new IsMention(this,this.isObserved)
  attr += new Dirty(this)
  attr += bagOfTruths
  override def removedChildHook(entity:Entity)(implicit d:DiffList)={super.removedChildHook(entity);exists.set(this.isConnected)(d);dirty++}
  override def addedChildHook(entity:Entity)(implicit d:DiffList)={super.addedChildHook(entity);exists.set(this.isConnected)(d);dirty++}
  override def changedParentEntityHook(oldEntity:Entity,newEntity:Entity)(implicit d:DiffList){super.changedParentEntityHook(oldEntity,newEntity);isEntity.set(this.isRoot)(d);exists.set(this.isConnected)(d)}
}

abstract class HierEntityCubbie extends EntityCubbie{
  val isMention = BooleanSlot("isMention")
  override def finishFetchEntity(e:Entity):Unit ={
    e.attr[IsMention].set(isMention.value)(null)
    e.isObserved=isMention.value
    e.attr[IsEntity].set(e.isRoot)(null)
    e.attr[EntityExists].set(e.isConnected)(null)
  }
  override def finishStoreEntity(e:Entity):Unit ={
    isMention := e.attr[IsMention].booleanValue
  }
}
trait Prioritizable{
  var priority:Double=0.0
}
trait HasCanopyAttributes[T<:Entity]{
  val canopyAttributes = new ArrayBuffer[CanopyAttribute[T]]
}
trait CanopyAttribute[T<:Entity]{def entity:Entity;def canopyName:String}

/**Mix this into the sampler and it will automatically propagate the bags that you define in the appropriate method*/
/*
trait AutomaticBagPropagation{
  def bagsToPropgate(e:Entity):Seq[BagOfWordsVariable]
}
*/

abstract class HierCorefSampler[T<:HierEntity](model:TemplateModel) extends SettingsSampler[Null](model, null) {
  def timeAndProcess(n:Int):Unit = super.process(n) //temporary fix to no longer being able to override process.
  def newEntity:T
  //def reestimateAttributes(e:T):Unit 
  protected var entities:ArrayBuffer[T] = null
  protected var deletedEntities:ArrayBuffer[T] = null
  def getEntities = entities.filter(_.isConnected)
  def getDeletedEntities = {
    val deleted = new HashSet[T]
    for(d<-deletedEntities)deleted += d
    for(d<-entities)if(!d.isConnected)deleted += d
    //performMaintenance(entities)
    //deletedEntities
    deleted.toSeq
  }
  def infer(numSamples:Int):Unit ={}
  /**Returns a random entity that 'exists'*/
  //def nextEntity:T = nextEntity(null.asInstanceOf[T])
  def nextEntity:T=nextEntity(null.asInstanceOf[T])
  def nextEntity(context:T):T=sampleEntity(entities)
  def nextEntityPair:(T,T) = {
    val e1 = nextEntity
    val e2 = nextEntity(e1)
    (e1,e2)
  }
  def sampleAttributes(e:T)(implicit d:DiffList):Unit //= {e.dirty.reset}
  protected def sampleEntity(samplePool:ArrayBuffer[T]) = {
    val initialSize = samplePool.size
    var tries = 10
    var e:T = null.asInstanceOf[T]
    while({tries-=1;tries} >= 0 && (e==null || !e.isConnected) && samplePool.size>0){
      e = samplePool(random.nextInt(samplePool.size))
      if(tries==1)performMaintenance(samplePool)
    }
    //if(e!=null && !e.isConnected)throw new Exception("NOT CONNECTED")
    if(!e.isConnected)e==null.asInstanceOf[T]
    e
  }
  def setEntities(ents:Iterable[T]) = {entities = new ArrayBuffer[T];for(e<-ents)addEntity(e);deletedEntities = new ArrayBuffer[T]}
  /**Garbage collects all the deleted entities from the master list of entities*/
  def performMaintenance(es:ArrayBuffer[T]):Unit ={
    //println("Performing maintenance")
    var oldSize = es.size
    val cleanEntities = new ArrayBuffer[T]
    cleanEntities ++= es.filter(_.isConnected)
    deletedEntities ++= es.filter(!_.isConnected)
    es.clear
    es++=cleanEntities
    println("  removed "+(oldSize-es.size)+ " disconnected entities. new size:"+es.size)
  }
  //def newDiffList2 = new cc.factorie.example.DebugDiffList
  /**This function randomly generates a list of jumps/proposals to choose from.*/
  def settings(c:Null) : SettingIterator = new SettingIterator {
    val changes = new scala.collection.mutable.ArrayBuffer[(DiffList)=>Unit]
    val (entity1,entity2) = nextEntityPair
    if (entity1.entityRoot.id != entity2.entityRoot.id) { //sampled nodes refer to different entities
      if(!isMention(entity1)){
        changes += {(d:DiffList) => mergeLeft(entity1,entity2)(d)} //what if entity2 is a mention?
        if(entity1.id != entity1.entityRoot.id) //avoid adding the same jump to the list twice
          changes += {(d:DiffList) => mergeLeft(entity1.entityRoot.asInstanceOf[T],entity2)(d)} //unfortunately casting is necessary unless we want to type entityRef/parentEntity/childEntities
      }
      if(entity1.parentEntity==null && entity2.parentEntity==null)
        changes += {(d:DiffList) => mergeUp(entity1,entity2)(d)}
      else
        changes += {(d:DiffList) => mergeUp(entity1.entityRoot.asInstanceOf[T],entity2.entityRoot.asInstanceOf[T])(d)}
    } else { //sampled nodes refer to same entity
      changes += {(d:DiffList) => splitRight(entity1,entity2)(d)}
      changes += {(d:DiffList) => splitRight(entity2,entity1)(d)}
      if(entity1.parentEntity != null && !entity1.isObserved)
        changes += {(d:DiffList) => {collapse(entity1)(d)}}
    }
    if(entity1.dirty.value>0 && !entity1.isObserved)changes += {(d:DiffList) => sampleAttributes(entity1)(d)}
    if(entity1.entityRoot.id != entity1.id && entity1.entityRoot.attr[Dirty].value>0 && !entity1.entityRoot.isObserved)changes += {(d:DiffList) => sampleAttributes(entity1.entityRoot.asInstanceOf[T])(d)}

    changes += {(d:DiffList) => {}} //give the sampler an option to reject all other proposals
    var i = 0
    def hasNext = i < changes.length
    def next(d:DiffList) = {val d = newDiffList; changes(i).apply(d); i += 1; d }
    def reset = i = 0
  }
  /**Removes an intermediate node in the tree, merging that nodes children to their grandparent.*/
  def collapse(entity:T)(implicit d:DiffList):Unit ={
    if(entity.parentEntity==null)throw new Exception("Can't collapse a node that is the root of a tree.")
    val root = entity.entityRoot
    //println("ROOT1:"+cc.factorie.example.Coref3.entityString(root))
    //println("entity:"+cc.factorie.example.Coref3.entityString(entity))
    //println("checking 1")
    //cc.factorie.example.Coref3.checkIntegrity(entity)
    val oldParent = entity.parentEntity
    //entity.childEntitiesIterator.foreach(_.setParentEntity(entity.parentEntity)(d))
    //println("  num children: "+entity.childEntitiesSize)
//    val childrenCopy = new ArrayBuffer[Entity];childrenCopy ++= entity.childEntitiesIterator
//    for(child <- childrenCopy)child.setParentEntity(entity.parentEntity)(d)
    for(child <- entity.safeChildEntitiesSeq)child.setParentEntity(entity.parentEntity)(d)
    //println("checking 2")
    //cc.factorie.example.Coref3.checkIntegrity(entity)
    entity.setParentEntity(null)(d)
    //println("checking 3")
    //cc.factorie.example.Coref3.checkIntegrity(root)
    //println("ROOT3:"+cc.factorie.example.Coref3.entityString(root))

  }
  /**Peels off the entity "right", does not really need both arguments unless we want to error check.*/
  def splitRight(left:T,right:T)(implicit d:DiffList):Unit ={
    val oldParent = right.parentEntity
    right.setParentEntity(null)(d)
    structurePreservationForEntityThatLostChild(oldParent)(d)
  }
  /**Jump function that proposes merge: entity1<----entity2*/
  def mergeLeft(entity1:T,entity2:T)(implicit d:DiffList):Unit ={
    val oldParent = entity2.parentEntity
    entity2.setParentEntity(entity1)(d)
    structurePreservationForEntityThatLostChild(oldParent)(d)
  }
  /**Jump function that proposes merge: entity1--->NEW-PARENT-ENTITY<---entity2 */
  def mergeUp(e1:T,e2:T)(implicit d:DiffList):T = {
    val oldParent1 = e1.parentEntity
    val oldParent2 = e2.parentEntity
    val result = newEntity
    e1.setParentEntity(result)(d)
    e2.setParentEntity(result)(d)
    structurePreservationForEntityThatLostChild(oldParent1)(d)
    structurePreservationForEntityThatLostChild(oldParent2)(d)
    result
  }
  /**Ensure that chains are not created in our tree. No dangling children-entities either.*/
  protected def structurePreservationForEntityThatLostChild(e:Entity)(implicit d:DiffList):Unit ={
    if(e!=null && e.childEntitiesSize<=1){
      for(childEntity <- e.childEntities)
        childEntity.setParentEntity(e.parentEntity)
      e.setParentEntity(null)(d)
    }
  }
  /**Identify entities that are created by accepted jumps so we can add them to our master entity list.*/
  override def proposalHook(proposal:Proposal) = {
    super.proposalHook(proposal)
    val newEntities = new HashSet[T]
    proposal.diff.undo //an entity that does not exit in the current world is one that was newly created by the jump
    for(diff<-proposal.diff){
      diff.variable match{
        case children:ChildEntities => if(!children.entity.isConnected && !children.entity.isObserved)newEntities += children.entity.asInstanceOf[T] //cast could be avoided if children entities were typed
        case _ => {}
      }
    }
    proposal.diff.redo
    for(entity<-newEntities)addEntity(entity)
  }
  def addEntity(e:T):Unit ={entities += e}
  def isMention(e:Entity):Boolean = e.isObserved
}

abstract class ChildParentTemplateWithStatistics[A<:EntityAttr](implicit m:Manifest[A]) extends TemplateWithStatistics3[EntityRef,A,A] {
  def unroll1(er:EntityRef): Iterable[Factor] = if(er.dst!=null)Factor(er, er.src.attr[A], er.dst.attr[A]) else Nil
  def unroll2(childAttr:A): Iterable[Factor] = if(childAttr.entity.parentEntity!=null)Factor(childAttr.entity.parentEntityRef, childAttr, childAttr.entity.parentEntity.attr[A]) else Nil
  def unroll3(parentAttr:A): Iterable[Factor] = for(e<-parentAttr.entity.childEntities) yield Factor(e.parentEntityRef,e.attr[A],parentAttr)
}

/*
abstract class ChildParentTemplateWithStatistics[A<:EntityAttr](implicit m:Manifest[A]) extends TemplateWithStatistics3[EntityRef,A,A] {
  def unroll1(er:EntityRef) = if(er.dst!=null)Factor(er, er.src.attr[A], er.dst.attr[A]) else Nil
  def unroll2(childAttr:A) = if(childAttr.entity.parentEntity!=null)Factor(childAttr.entity.parentEntityRef, childAttr, childAttr.entity.parentEntity.attr[A]) else Nil
  def unroll3(parentAttr:A) = for(e<-parentAttr.entity.childEntities) yield Factor(e.parentEntityRef,e.attr[A],parentAttr)
}
*/

abstract class ChildParentTemplate[A<:EntityAttr](implicit m:Manifest[A]) extends Template3[EntityRef,A,A] {
  def unroll1(er:EntityRef): Iterable[Factor] = if(er.dst!=null)Factor(er, er.src.attr[A], er.dst.attr[A]) else Nil
  def unroll2(childAttr:A): Iterable[Factor] = if(childAttr.entity.parentEntity!=null)Factor(childAttr.entity.parentEntityRef, childAttr, childAttr.entity.parentEntity.attr[A]) else Nil
  def unroll3(parentAttr:A): Iterable[Factor] = for(e<-parentAttr.entity.childEntities) yield Factor(e.parentEntityRef,e.attr[A],parentAttr)
}



class StructuralPriorsTemplate(val entityExistenceCost:Double=2.0,subEntityExistenceCost:Double=0.5) extends TemplateWithStatistics3[EntityExists,IsEntity,IsMention]{
  def unroll1(exists:EntityExists) = Factor(exists,exists.entity.attr[IsEntity],exists.entity.attr[IsMention])
  def unroll2(isEntity:IsEntity) = Factor(isEntity.entity.attr[EntityExists],isEntity,isEntity.entity.attr[IsMention])
  def unroll3(isMention:IsMention) = throw new Exception("An entitie's status as a mention should never change.")
  def score(s:Stat):Double ={
    val exists:Boolean = s._1.booleanValue
    val isEntity:Boolean = s._2.booleanValue
    val isMention:Boolean = s._3.booleanValue
    var result = 0.0
    if(exists && isEntity) result -= entityExistenceCost
    if(exists && !isEntity && !isMention)result -= subEntityExistenceCost
    result
  }
}

/**Basic trait for doing operations with bags of words*/
trait BagOfWords{ // extends scala.collection.Map[String,Double]{
  //def empty: This
  def size:Int
  def asHashMap:HashMap[String,Double]
  def apply(word:String):Double
  def iterator:Iterator[(String,Double)]
  def l2Norm:Double
  def l1Norm:Double
  def *(that:BagOfWords):Double
  def deductedDot(that:BagOfWords, deduct:BagOfWords):Double
  def cosineSimilarityINEFFICIENT(that:BagOfWords,deduct:BagOfWords):Double ={
    //println("  (1) bag: "+that)
    //println("  (1) that   : "+that.l2Norm)
    //println("  (1) that bf: "+that.l2NormBruteForce)
    that.removeBag(deduct)
    //println("  (2) bag: "+that)
    //println("  (2) that   : "+that.l2Norm)
    //println("  (2) that bf: "+that.l2NormBruteForce)
    val result = cosineSimilarity(that)
    that.addBag(deduct)
    //println("  (3) bag: "+that)
    //println("  (3) that   : "+that.l2Norm)
    //println("  (3) that bf: "+that.l2NormBruteForce)
    result
  }
  def cosineSimilarity(that:BagOfWords,deduct:BagOfWords):Double ={
    //val smaller = if(this.size<that.size)this else that
    //val larger = if(that.size<this.size)this else that
    val numerator:Double = this.deductedDot(that,deduct)
    if(numerator==0.0){
      val thatL2Norm = Math.sqrt(deduct.l2Norm*deduct.l2Norm+that.l2Norm*that.l2Norm - 2*(deduct * that))
      val denominator:Double = this.l2Norm*thatL2Norm
      if(denominator==0.0 || denominator != denominator) 0.0 else numerator/denominator
    } else 0.0
  }
  def cosineSimilarity(that:BagOfWords):Double = {
    val numerator:Double = this * that
    val denominator:Double = this.l2Norm*that.l2Norm
    if(denominator==0.0 || denominator != denominator) 0.0 else numerator/denominator
  }
  def +=(s:String,w:Double=1.0):Unit
  def -=(s:String,w:Double=1.0):Unit
  def ++=(that:BagOfWords):Unit = for((s,w) <- that.iterator)this += (s,w)
  def --=(that:BagOfWords):Unit = for((s,w) <- that.iterator)this -= (s,w)
  def contains(s:String):Boolean
  def l2NormBruteForce:Double = {
    var result=0.0
    for((k,v) <- iterator)
      result += v*v
    scala.math.sqrt(result)
  }
  def addBag(that:BagOfWords):Unit
  def removeBag(that:BagOfWords):Unit
}

class SparseBagOfWords(initialWords:Iterable[String]=null,initialBag:Map[String,Double]=null) extends BagOfWords{
  var variable:BagOfWordsVariable = null
  protected var _l2Norm = 0.0
  protected var _l1Norm = 0.0
  protected var _bag = new LinkedHashMap[String,Double]//TODO: try LinkedHashMap
  if(initialWords!=null)for(w<-initialWords)this += (w,1.0)
  if(initialBag!=null)for((k,v)<-initialBag)this += (k,v)
  def l2Norm = scala.math.sqrt(_l2Norm)
  def l1Norm = _l1Norm
  def asHashMap:HashMap[String,Double] = {val result = new HashMap[String,Double];result ++= _bag;result}
  override def toString = _bag.toString
  def apply(s:String):Double = _bag.getOrElse(s,0.0)
  def contains(s:String):Boolean = _bag.contains(s)
  def size = _bag.size
  def iterator = _bag.iterator
  def *(that:BagOfWords) : Double = {
    if(that.size<this.size)return that * this
    var result = 0.0
    for((k,v) <- iterator)result += v*that(k)
    result
  }
  def deductedDot(that:BagOfWords,deduct:BagOfWords) : Double = {
    var result = 0.0
    for((k,v) <- iterator)result += v*(that(k) - deduct(k))
    result
  }
  def += (s:String, w:Double=1.0):Unit ={
    _l1Norm += w
    _l2Norm += w*w + 2*this(s)*w
    _bag(s) = _bag.getOrElse(s,0.0) + w
  }
  def -= (s:String, w:Double=1.0):Unit ={
    _l1Norm -= w
    _l2Norm += w*w - 2.0*this(s)*w
    if(w == _bag(s))_bag.remove(s)
    else _bag(s) = _bag.getOrElse(s,0.0) - w
  }
  def addBag(that:BagOfWords) = for((k,v) <- that.iterator) this += (k,v)
  def removeBag(that:BagOfWords) = for((k,v) <- that.iterator)this -= (k,v)
}
trait BagOfWordsVar extends Variable with VarAndValueGenericDomain[BagOfWordsVar,SparseBagOfWords] with Iterable[(String,Double)]
class BagOfWordsVariable(initialWords:Iterable[String]=Nil,initialMap:Map[String,Double]=null) extends BagOfWordsVar with VarAndValueGenericDomain[BagOfWordsVariable,SparseBagOfWords] {
  // Note that the returned value is not immutable.
  def value = _members
  private val _members:SparseBagOfWords = {
    val result = new SparseBagOfWords(initialWords)
    if(initialMap!=null)for((k,v) <- initialMap)result += (k,v)
    result
  }
  _members.variable = this
  def members: SparseBagOfWords = _members
  def iterator = _members.iterator
  override def size = _members.size
  def contains(x:String) = _members.contains(x)
  def accept:Unit ={} //_members.incorporateBags
  def add(x:String,w:Double=1.0)(implicit d:DiffList):Unit = {
    if(d!=null) d += new BagOfWordsVariableAddStringDiff(x,w)
    _members += (x,w)
  }
  def remove(x:String,w:Double = 1.0)(implicit d:DiffList):Unit = {
    if(d!=null) d += new BagOfWordsVariableRemoveStringDiff(x,w)
    _members -= (x,w)
  }
  def add(x:BagOfWords)(implicit d: DiffList): Unit =  {
    if (d != null) d += new BagOfWordsVariableAddBagDiff(x)
    _members.addBag(x)
  }
  def remove(x: BagOfWords)(implicit d: DiffList): Unit = {
    if (d != null) d += new BagOfWordsVariableRemoveBagDiff(x)
    _members.removeBag(x)
  }
  final def += (x:String,w:Double=1.0):Unit = add(x,w)(null)
  final def -= (x:String,w:Double=1.0):Unit = remove(x,w)(null)
  final def +=(x:BagOfWords): Unit = add(x)(null)
  final def -=(x:BagOfWords): Unit = remove(x)(null)
  final def ++=(xs:Iterable[String]): Unit = xs.foreach(add(_)(null))
  final def --=(xs:Iterable[String]): Unit = xs.foreach(remove(_)(null))
  final def ++=(xs:HashMap[String,Double]): Unit = for((k,v)<-xs)add(k,v)(null)
  final def --=(xs:HashMap[String,Double]): Unit = for((k,v)<-xs)remove(k,v)(null)
  case class BagOfWordsVariableAddStringDiff(added: String,w:Double) extends Diff {
    // Console.println ("new SetVariableAddDiff added="+added)
    def variable: BagOfWordsVariable = BagOfWordsVariable.this
    def redo = _members += (added,w)
    def undo = _members -= (added,w)
    override def toString = "BagOfWordsVariableAddStringDiff of " + added + " to " + BagOfWordsVariable.this
  }
  case class BagOfWordsVariableRemoveStringDiff(removed: String,w:Double) extends Diff {
    //        Console.println ("new SetVariableRemoveDiff removed="+removed)
    def variable: BagOfWordsVariable = BagOfWordsVariable.this
    def redo = _members -= (removed,w)
    def undo = _members += (removed,w)
    override def toString = "BagOfWordsVariableRemoveStringDiff of " + removed + " from " + BagOfWordsVariable.this
  }
  case class BagOfWordsVariableAddBagDiff(added:BagOfWords) extends Diff {
    // Console.println ("new SetVariableAddDiff added="+added)
    def variable: BagOfWordsVariable = BagOfWordsVariable.this
    def redo = _members.addBag(added)
    def undo = _members.removeBag(added)
    override def toString = "BagOfWordsVariableAddBagDiff of " + added + " to " + BagOfWordsVariable.this
  }
  case class BagOfWordsVariableRemoveBagDiff(removed: BagOfWords) extends Diff {
    //        Console.println ("new SetVariableRemoveDiff removed="+removed)
    def variable: BagOfWordsVariable = BagOfWordsVariable.this
    def redo = _members.removeBag(removed)
    def undo = _members.addBag(removed)
    override def toString = "BagOfWordsVariableRemoveBagDiff of " + removed + " from " + BagOfWordsVariable.this
  }
}
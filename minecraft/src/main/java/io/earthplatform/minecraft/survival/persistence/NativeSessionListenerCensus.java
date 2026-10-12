package io.earthplatform.minecraft.survival.persistence;

import io.earthplatform.minecraft.survival.world.SurvivalRefusal;
import java.lang.reflect.*;
import java.util.*;
import java.util.function.Consumer;
import net.neoforged.bus.EventBus;
import net.neoforged.bus.ListenerList;
import net.neoforged.bus.GeneratedEventListener;
import net.neoforged.bus.SubscribeEventListener;
import net.neoforged.bus.api.*;
import net.neoforged.bus.api.EventListener;

/** Read-only inherited listener census. Native registration/dispatch are never suppressed or invoked.
 * Known instance subscribers use the pinned factory's unique Method implementation class.
 * Known Consumers require an actual registration-origin witness, not lambda class-name inference.
 */
final class NativeSessionListenerCensus {
 record Target(String owner,String method,NativeSessionCallbackWitness.Site site){
  static Target subscriber(String owner,String method){return new Target(owner,method,null);}
  static Target witnessed(NativeSessionCallbackWitness.Site site){return new Target(site.host,"witness:"+site,site);}
 }
 record Row(String event,String priority,String wrapper,String target){}
 private final EventBus bus;
 private final Map<String,List<EventListener>> retained=new HashMap<>();
 private final List<Row> rows=new ArrayList<>();
 NativeSessionListenerCensus(IEventBus bus){
  if(bus.getClass()!=EventBus.class)throw new SurvivalRefusal("Exact native EventBus required");this.bus=(EventBus)bus;
  for(String n:List.of("EventBus","ListenerList","SubscribeEventListener","GeneratedEventListener","EventListenerFactory","ConsumerEventHandler","ConsumerEventHandler$WithPredicate"))
   NativeSessionCallbackPins.require("net.neoforged.bus."+n);
 }
 List<Row> rows(){return List.copyOf(rows);}
 void require(String eventName,Target...targets){
  Class<?> event=type(eventName);if(!Event.class.isAssignableFrom(event))throw refused("Unrecognized event class");
  ListenerList list=(ListenerList)invoke(EventBus.class,bus,"getListenerList",new Class<?>[]{Class.class},event);
  // Inspect the original inherited priority rosters BEFORE asking native cache-building to
  // unwrap them; unknown wrappers must not gain a getWithoutCheck invocation during admission.
  var phases=new LinkedHashMap<EventPriority,List<EventListener>>();int total=0;
  for(EventPriority priority:EventPriority.values()){
   @SuppressWarnings("unchecked") var phase=(List<EventListener>)invoke(ListenerList.class,list,"getListeners",new Class<?>[]{EventPriority.class},priority);
   if(phase.size()>16)throw refused("Callback census bound exceeded");phases.put(priority,phase);total+=phase.size();
  }
  if(total!=targets.length)throw refused("Unexpected effective callback count for "+eventName+": "+total+" expected "+targets.length+" raw="+describe(phases));
  Set<Target> unmatched=new HashSet<>(List.of(targets));
  List<EventListener> actual=new ArrayList<>();
  for(var phaseEntry:phases.entrySet()){
   EventPriority priority=phaseEntry.getKey();
   for(EventListener listener:phaseEntry.getValue()){
    Target match=null;
    for(Target target:unmatched)if(matches(listener,target,event,priority)){match=target;break;}
    if(match==null)throw refused("Unadmitted callback: "+eventName+"/"+priority+"/"+listener.getClass().getName());
    unmatched.remove(match);actual.add(listener);
    Row row=new Row(eventName,priority.name(),listener.getClass().getName(),match.owner()+"#"+match.method());
    if(!rows.contains(row))rows.add(row);
   }
  }
  if(!unmatched.isEmpty() || actual.size()!=total)throw refused("Native callback roster incomplete");
  // Pinned ListenerList strips cancellation wrappers for non-cancellable event types. Apply
  // that same container-only routine to a fresh local copy after every wrapper is authenticated.
  var expectedEffective=new ArrayList<>(actual);
  invoke(ListenerList.class,list,"unwrapListeners",new Class<?>[]{List.class},expectedEffective);
  if(!sameIdentity(expectedEffective,Arrays.asList(list.getListeners())))throw refused("Effective event cache/priority inventory disagreement");
  List<EventListener> prior=retained.putIfAbsent(eventName,List.copyOf(actual));
  if(prior!=null && !sameIdentity(prior,actual))throw refused("Native callback identities changed after admission: "+eventName);
 }
 private static List<String> describe(Map<EventPriority,List<EventListener>> phases){
  var report=new ArrayList<String>();
  phases.forEach((priority,listeners)->listeners.forEach(listener->report.add(priority+"/"+listener.getClass().getName()+"@"+Integer.toHexString(System.identityHashCode(listener)))));
  return List.copyOf(report);
 }
 private boolean matches(EventListener listener,Target target,Class<?> event,EventPriority priority){
  NativeSessionCallbackPins.require(target.owner());
  if(target.site()!=null){
   if(priority!=EventPriority.NORMAL || listener.getClass()!=type("net.neoforged.bus.ConsumerEventHandler$WithPredicate"))return false;
   Class<?> base=type("net.neoforged.bus.ConsumerEventHandler");
   Object consumer=field(base,listener,"consumer");
   if(consumer!=NativeSessionCallbackWitness.require(target.site()))return false;
   Object nativePredicate=invoke(EventBus.class,bus,"passNotGenericFilter",new Class<?>[]{boolean.class},false);
   return field(listener.getClass(),listener,"predicate")==nativePredicate;
  }
  if(listener.getClass()!=SubscribeEventListener.class)return false;
  try{
   Class<?> owner=type(target.owner());Method method=owner.getDeclaredMethod(target.method(),event);
   SubscribeEvent expected=method.getAnnotation(SubscribeEvent.class);
   if(method.getReturnType()!=void.class || !Modifier.isPublic(method.getModifiers()) || expected==null
      || expected.priority()!=priority || expected.receiveCanceled())return false;
   var subscribed=(SubscribeEventListener)listener;var handler=subscribed.getWithoutCheck();
   if(handler.getClass().getSuperclass()!=GeneratedEventListener.class)return false;
   Object receiver=null;
   if(Modifier.isStatic(method.getModifiers())){if(handler.getClass().getDeclaredFields().length!=0)return false;}
   else{
    Field[] fields=handler.getClass().getDeclaredFields();
    if(fields.length!=1 || !fields[0].getName().equals("instance") || fields[0].getType()!=Object.class)return false;
    receiver=field(handler.getClass(),handler,"instance");if(receiver==null || receiver.getClass()!=owner)return false;
   }
   var reference=new SubscribeEventListener(receiver,method).getWithoutCheck();
   SubscribeEvent actual=(SubscribeEvent)field(SubscribeEventListener.class,subscribed,"subInfo");
   return handler.getClass()==reference.getClass() && actual.annotationType()==SubscribeEvent.class
    && actual.priority()==expected.priority() && !actual.receiveCanceled() && subscribed.getPriority()==priority;
  }catch(NoSuchMethodException error){throw refused("Pinned callback method missing: "+target);}
 }
 static boolean sameIdentity(List<?> before,List<?> after){if(before.size()!=after.size())return false;for(int i=0;i<before.size();i++)if(before.get(i)!=after.get(i))return false;return true;}
 static Class<?> type(String name){try{return Class.forName(name,false,NativeSessionListenerCensus.class.getClassLoader());}catch(ClassNotFoundException error){throw refused("Pinned type unavailable: "+name);}}
 static Object field(Class<?> owner,Object receiver,String name){try{Field field=owner.getDeclaredField(name);if(!field.trySetAccessible())throw refused("Pinned field inaccessible: "+owner+"#"+name);return field.get(receiver);}catch(ReflectiveOperationException error){throw refused("Pinned field unavailable: "+owner+"#"+name);}}
 static Object invoke(Class<?> owner,Object receiver,String name,Class<?>[] parameters,Object...args){try{Method method=owner.getDeclaredMethod(name,parameters);if(!method.trySetAccessible())throw refused("Pinned method inaccessible: "+name);return method.invoke(receiver,args);}catch(ReflectiveOperationException error){throw refused("Pinned inspection unavailable: "+owner+"#"+name+": "+error);}}
 static SurvivalRefusal refused(String text){return new SurvivalRefusal(text);}
}

package io.earthplatform.minecraft.survival.interaction.host;

import io.earthplatform.minecraft.survival.world.SurvivalRefusal;
import java.lang.ref.Reference;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Owner accounting tests only; transformed constructors are exercised by actual GameTests. */
class NativePredicateConstructorWitnessTest {
    @Test void witnessesPinExactOwnerTermsChildrenAndCacheIdentity() {
        var book=new NativePlacedBlockCriterionAdmission.WitnessBook(true,4,8,4096);
        Object owner=new Object(),child=new Object(),cache=new Object();List<?> terms=List.of(child);
        book.record(owner,terms,cache);assertDoesNotThrow(()->book.require(owner,terms,cache));
        assertThrows(SurvivalRefusal.class,()->book.require(new Object(),terms,cache));
        assertThrows(SurvivalRefusal.class,()->book.require(owner,List.of(child),cache));
        assertThrows(SurvivalRefusal.class,()->book.require(owner,terms,new Object()));
        assertEquals(1,book.usage().owners());assertEquals(1,book.usage().edges());assertEquals(320,book.usage().bytes());
    }
    @Test void arbitraryEqualsOrHashCodeCannotRunInIdentityBook() {
        class Hostile { @Override public int hashCode(){throw new AssertionError();} @Override public boolean equals(Object o){throw new AssertionError();} }
        var book=new NativePlacedBlockCriterionAdmission.WitnessBook(true,4,8,4096);
        Object a=new Hostile(),b=new Hostile(),cache=new Object();List<?> terms=List.of(new Hostile());
        book.record(a,terms,cache);assertDoesNotThrow(()->book.require(a,terms,cache));
        assertThrows(SurvivalRefusal.class,()->book.require(b,terms,cache));
    }
    @Test void mutableAndCustomCollectionsAreNotInspectedOrWitnessed() {
        var book=new NativePlacedBlockCriterionAdmission.WitnessBook(true,4,8,4096);Object owner=new Object(),cache=new Object();
        List<?> mutable=new ArrayList<>(List.of(new Object()));book.record(owner,mutable,cache);
        assertThrows(SurvivalRefusal.class,()->book.require(owner,mutable,cache));
        List<Object> hostile=new java.util.AbstractList<>() {
            public int size(){throw new AssertionError("custom size executed");} public Object get(int i){throw new AssertionError("custom get executed");}
        };
        assertDoesNotThrow(()->book.record(new Object(),hostile,cache));assertEquals(0,book.usage().owners());
    }
    @Test void duplicateRegistrationNeverReplacesConstructorAuthority() {
        var book=new NativePlacedBlockCriterionAdmission.WitnessBook(true,4,8,4096);Object owner=new Object(),a=new Object(),b=new Object();List<?> terms=List.of(a);
        book.record(owner,terms,a);book.record(owner,terms,b);
        assertDoesNotThrow(()->book.require(owner,terms,a));assertThrows(SurvivalRefusal.class,()->book.require(owner,terms,b));assertEquals(320,book.usage().bytes());
    }
    @Test void ownerEdgeAndByteOverflowStayStickyWithoutThrowingFromCapture() {
        for (var book:List.of(new NativePlacedBlockCriterionAdmission.WitnessBook(true,1,8,4096),
                new NativePlacedBlockCriterionAdmission.WitnessBook(true,4,1,4096),new NativePlacedBlockCriterionAdmission.WitnessBook(true,4,8,639))) {
            Object a=new Object(),b=new Object(),cache=new Object();List<?> terms=List.of(cache);
            book.record(a,terms,cache);assertDoesNotThrow(()->book.record(b,terms,cache));
            assertTrue(book.usage().overflow());assertEquals(1,book.usage().owners());assertEquals(320,book.usage().bytes());
            assertThrows(SurvivalRefusal.class,()->book.require(a,terms,cache));
        }
    }
    @Test void referenceQueueRetirementReleasesOnlyItsExactChargedOwner() throws Exception {
        var book=new NativePlacedBlockCriterionAdmission.WitnessBook(true,4,8,4096);Object a=new Object(),b=new Object(),cache=new Object();List<?> terms=List.of(cache);
        book.record(a,terms,cache);book.record(b,terms,cache);
        var field=book.getClass().getDeclaredField("entries");assertTrue(field.trySetAccessible());
        var entries=(Map<?,?>)field.get(book);Reference<?> first=(Reference<?>)entries.keySet().iterator().next();
        Object removed=first.get();first.clear();assertTrue(first.enqueue()); // Explicit unit queue fixture, not a GC timing claim.
        assertEquals(1,book.usage().owners());assertEquals(1,book.usage().edges());assertEquals(320,book.usage().bytes());
        assertDoesNotThrow(()->book.require(removed==a?b:a,terms,cache));
    }
    @Test void weakWitnessValuesDoNotRetainOwnerOrItsChildrenStrongly() throws Exception {
        var book=new NativePlacedBlockCriterionAdmission.WitnessBook(true,4,8,4096);Object owner=new Object(),cache=new Object();List<?> terms=List.of(owner);
        book.record(owner,terms,cache);
        var field=book.getClass().getDeclaredField("entries");assertTrue(field.trySetAccessible());var entries=(Map<?,?>)field.get(book);
        Object witness=entries.values().iterator().next();
        for (var component:witness.getClass().getRecordComponents()) {
            var accessor=component.getAccessor();assertTrue(accessor.trySetAccessible());Object value=accessor.invoke(witness);
            if (value instanceof List<?> children) for (Object child:children) assertInstanceOf(java.lang.ref.WeakReference.class,child);
            else assertTrue(value instanceof java.lang.ref.WeakReference<?> || value instanceof Integer);
        }
    }
    @Test void disabledBookNeverMintsAuthority() {
        var book=new NativePlacedBlockCriterionAdmission.WitnessBook(false,4,8,4096);Object owner=new Object(),cache=new Object();List<?> terms=List.of(cache);
        book.record(owner,terms,cache);assertEquals(0,book.usage().owners());assertThrows(SurvivalRefusal.class,()->book.require(owner,terms,cache));
    }
    @Test void constructorStackCannotBeForgedByADirectCaller() throws Exception {
        var method=NativePlacedBlockCriterionAdmission.class.getDeclaredMethod("constructorCaller",Class.class);assertTrue(method.trySetAccessible());
        assertEquals(false,method.invoke(null,net.minecraft.advancements.critereon.ContextAwarePredicate.class));
        assertEquals(false,method.invoke(null,net.minecraft.world.level.storage.loot.predicates.CompositeLootItemCondition.class));
    }
    @Test void sameNamedForeignLoaderImmutableListCannotMintAWitness() throws Exception {
        ClassLoader loader=new ClassLoader(getClass().getClassLoader()) {
            @Override protected Class<?> loadClass(String name,boolean resolve) throws ClassNotFoundException {
                if (!name.startsWith("com.google.common.collect.")) return super.loadClass(name,resolve);
                synchronized(getClassLoadingLock(name)) {
                    Class<?> loaded=findLoadedClass(name);
                    if (loaded==null) try (var input=getParent().getResourceAsStream(name.replace('.','/')+".class")) {
                        if (input==null) throw new ClassNotFoundException(name);byte[] bytes=input.readAllBytes();
                        loaded=defineClass(name,bytes,0,bytes.length);
                    } catch (java.io.IOException failure) { throw new ClassNotFoundException(name,failure); }
                    if(resolve)resolveClass(loaded);return loaded;
                }
            }
        };
        var type=Class.forName("com.google.common.collect.ImmutableList",true,loader);
        List<?> foreign=(List<?>)type.getMethod("of",Object.class).invoke(null,new Object());
        assertEquals("com.google.common.collect.SingletonImmutableList",foreign.getClass().getName());
        assertNotSame(com.google.common.collect.ImmutableList.class.getClassLoader(),foreign.getClass().getClassLoader());
        var book=new NativePlacedBlockCriterionAdmission.WitnessBook(true,4,8,4096);Object owner=new Object(),cache=new Object();
        book.record(owner,foreign,cache);assertEquals(0,book.usage().owners());assertThrows(SurvivalRefusal.class,()->book.require(owner,foreign,cache));
    }
}

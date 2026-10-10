package io.earthplatform.minecraft.coregate;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;

/** One owner-thread recovery intent; old replacement objects never form an unbounded queue. */
final class CoreRecoverySlot<T> {
    record Entry<T>(UUID playerId,T player,CoreReturnPoint origin){}
    private Entry<T> current;
    boolean offer(UUID id,T player,CoreReturnPoint origin,Predicate<T> stale){
        Objects.requireNonNull(id);Objects.requireNonNull(player);Objects.requireNonNull(origin);Objects.requireNonNull(stale);
        if(current!=null&&!current.playerId().equals(id)&&!stale.test(current.player()))return false;
        current=new Entry<>(id,player,origin);return true;
    }
    Entry<T> current(){return current;}
    boolean matches(Entry<T> entry,Optional<CoreReturnPoint> recorded){return current==entry&&recorded.filter(entry.origin()::equals).isPresent();}
    void clear(Entry<T> entry){if(current==entry)current=null;}
}

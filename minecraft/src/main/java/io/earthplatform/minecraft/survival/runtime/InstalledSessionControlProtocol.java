package io.earthplatform.minecraft.survival.runtime;

import java.util.UUID;

/** Data-only strict parser. Parsed text is never authority; the native receiver/player and current
 * privately published session/epoch/sequence must also match before occupying the one-slot inbox.
 */
final class InstalledSessionControlProtocol {
    enum Action { RETURN, STATUS, RETRY_CHECKPOINT }
    record Frame(Action action, UUID session, UUID epoch, long sequence) {}
    private InstalledSessionControlProtocol() {}
    static Frame parse(String command) {
        if (command==null || command.length()>160 || !command.startsWith("earthsurvival_control ")) return null;
        String[] fields=command.split(" ",-1);
        if (fields.length!=5 || !fields[0].equals("earthsurvival_control")) return null;
        Action action;
        if (fields[1].equals("return")) action=Action.RETURN;
        else if (fields[1].equals("status")) action=Action.STATUS;
        else if (fields[1].equals("retry_checkpoint")) action=Action.RETRY_CHECKPOINT;
        else return null;
        if (!fields[4].matches("[1-9][0-9]{0,18}")) return null;
        try {
            UUID session=UUID.fromString(fields[2]), epoch=UUID.fromString(fields[3]);
            if (!session.toString().equals(fields[2]) || !epoch.toString().equals(fields[3])) return null;
            long sequence=Long.parseLong(fields[4]);
            return new Frame(action,session,epoch,sequence);
        } catch (IllegalArgumentException failure) { return null; }
    }
}

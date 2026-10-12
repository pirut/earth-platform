package io.earthplatform.minecraft.coregate;

import io.earthplatform.minecraft.enginebridge.RollingCubeActor.StageMetadata;
import io.earthplatform.minecraft.enginebridge.RollingCubeActor.StageReady;

/** Pure admission shared by server and replica; never acquires a ticket or grants edit authority. */
final class RollingFocusTransition {
    private RollingFocusTransition() {}
    static void requireAllowed(RollingSessionDescriptor descriptor, StageMetadata next, StageReady current) {
        if(next.currentFocus().equals(next.pinCenter()))return;
        if(descriptor==null||!descriptor.twoWorkspaces()||current==null||current.replacementSet()==null
                ||!descriptor.workspaces().equals(current.replacementSet().workspaces())
                ||!descriptor.workspaces().contains(next.currentFocus())||!descriptor.workspaces().contains(next.pinCenter())
                ||current.generation()!=next.generation()-1||!current.candidateCenter().equals(next.currentCenter())
                ||!next.currentFocus().equals(current.activeFocus())||current.pinned()!=next.currentPinned()
                ||!next.candidatePinned()||next.initial()
                ||!next.currentCenter().equals(next.candidateCenter())
                ||!descriptor.workspaces().insideWindow(next.pinCenter(),next.currentCenter())
                ||next.currentPinned()&&(!descriptor.adjacentWorkspaces()||!descriptor.workspaces().insideWindow(next.currentFocus(),next.currentCenter()))
                ||!next.newPositions().isEmpty()||!next.retiredPositions().isEmpty()
                ||!next.candidateLeases().equals(next.currentLeases()))
            throw new IllegalArgumentException("Focus handoff requires one exact current set and resident same-center neighborhoods");
    }
}

/*
 * Copyright (c) 2017-present, Facebook, Inc.
 * All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the
 * LICENSE file in the root directory of this source tree. An additional grant
 * of patent rights can be found in the PATENTS file in the same directory.
 */

package com.facebook.litho.sections;

import static com.facebook.litho.FrameworkLogEvents.EVENT_SECTIONS_GENERATE_CHANGESET;
import static com.facebook.litho.sections.Section.acquireChildrenMap;
import static com.facebook.litho.sections.Section.releaseChildrenMap;

import android.support.annotation.Nullable;
import android.support.v4.util.Pair;
import android.util.SparseArray;
import com.facebook.litho.ComponentsLogger;
import com.facebook.litho.LogEvent;
import com.facebook.litho.sections.logger.SectionsDebugLogger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * ChangeSetState is responsible to generate a global ChangeSet between two {@link Section}s
 * trees.
 */
public class ChangeSetState {

  private static final List<Section> sEmptyList = new ArrayList<>();

  private Section mCurrentRoot;
  private Section mNewRoot;
  private ChangeSet mChangeSet;
  private List<Section> mRemovedComponents;

  private ChangeSetState() {
    mRemovedComponents = new ArrayList<>();
  }

  /**
   * Calculate the {@link ChangeSet} for this ChangeSetState. The returned ChangeSet will be the
   * result of merging all the changeSets for all the leafs of the tree. As a result of calculating
   * the {@link ChangeSet} all the nodes in the new tree will be populated with the number of items
   * in their subtree.
   */
  static ChangeSetState generateChangeSet(
      SectionContext sectionContext,
      @Nullable Section currentRoot,
      Section newRoot,
      SectionsDebugLogger sectionsDebugLogger,
      String sectionTreeTag,
      String currentPrefix,
      String nextPrefix) {
    ChangeSetState changeSetState = acquireChangeSetState();
    changeSetState.mCurrentRoot = currentRoot;
    changeSetState.mNewRoot = newRoot;

    final ComponentsLogger logger = sectionContext.getLogger();
    LogEvent logEvent = null;
    if (logger != null) {
      logEvent =
          SectionsLogEventUtils.getSectionsPerformanceEvent(
              logger,
              sectionContext.getLogTag(),
              EVENT_SECTIONS_GENERATE_CHANGESET,
              currentRoot,
              newRoot);
    }

    changeSetState.mChangeSet =
        generateChangeSetRecursive(
            sectionContext,
            currentRoot,
            newRoot,
            changeSetState.mRemovedComponents,
            sectionsDebugLogger,
            sectionTreeTag,
            currentPrefix,
            nextPrefix,
            Thread.currentThread().getName());

    if (logger != null) {
      logger.log(logEvent);
    }

    return changeSetState;
  }

  private static ChangeSet generateChangeSetRecursive(
      SectionContext sectionContext,
      Section currentRoot,
      Section newRoot,
      List<Section> removedComponents,
      SectionsDebugLogger sectionsDebugLogger,
      String sectionTreeTag,
      String currentPrefix,
      String newPrefix,
      String thread) {

    boolean currentRootIsNull = currentRoot == null;
    boolean newRootIsNull = newRoot == null;

    if (currentRootIsNull  && newRootIsNull) {
      throw new IllegalStateException("Both currentRoot and newRoot are null.");
    }

    if (newRootIsNull) {
      // The new tree doesn't have this component. We only need to remove all its children from
      // the list.
      final int currentItemsCount = currentRoot.getCount();
      removedComponents.add(currentRoot);
      final ChangeSet changeSet = ChangeSet.acquireChangeSet(currentRoot.getCount());

      for (int i = 0; i < currentItemsCount; i++) {
        changeSet.addChange(Change.remove(0));
      }

      return changeSet;
    }

    final SectionLifecycle lifecycle = newRoot;
    final String updateCurrentPrefix = updatePrefix(currentRoot, currentPrefix);
    final String updateNewPrefix = updatePrefix(newRoot, newPrefix);

    // Components both exist and don't need to update.
    if (!currentRootIsNull && !lifecycle.shouldComponentUpdate(currentRoot, newRoot)) {
      final ChangeSet changeSet = ChangeSet.acquireChangeSet(currentRoot.getCount());
      newRoot.setCount(changeSet.getCount());
      sectionsDebugLogger.logShouldUpdate(
          sectionTreeTag,
          currentRoot,
          newRoot,
          updateCurrentPrefix,
          updateNewPrefix,
          false,
          thread);
      return changeSet;
    }

    sectionsDebugLogger.logShouldUpdate(
        sectionTreeTag, currentRoot, newRoot, updateCurrentPrefix, updateNewPrefix, true, thread);

    // Component(s) can generate changeSets and will generate the changeset.
    // Add the startCount to the changeSet.
    if (lifecycle.isDiffSectionSpec()) {
      ChangeSet changeSet =
          ChangeSet.acquireChangeSet(currentRootIsNull ? 0 : currentRoot.getCount());
      lifecycle.generateChangeSet(newRoot.getScopedContext(), changeSet, currentRoot, newRoot);
      newRoot.setCount(changeSet.getCount());

      return changeSet;
    }

    ChangeSet resultChangeSet = ChangeSet.acquireChangeSet();

    final Map<String, Pair<Section, Integer>> currentChildren = acquireChildrenMap(currentRoot);
    final Map<String, Pair<Section, Integer>> newChildren = acquireChildrenMap(newRoot);

    List<Section> currentChildrenList;
    if (currentRoot == null) {
      currentChildrenList = sEmptyList;
    } else {
      currentChildrenList = new ArrayList<>(currentRoot.getChildren());
    }

    final List<Section> newChildrenList = newRoot.getChildren();

    // Determine Move Changes.
    // Index of a section that was detected as moved.
    // Components that have swapped order with this one in the new list will be moved.
    int sectionToSwapIndex = -1;
    int swapToIndex = -1;

    for (int i = 0; i < newChildrenList.size(); i++) {
      final String key = newChildrenList.get(i).getGlobalKey();

      if (currentChildren.containsKey(key)) {
        final Pair<Section, Integer> valueAndPosition = currentChildren.get(key);
        final Section current = valueAndPosition.first;
        final int currentIndex = valueAndPosition.second;

        // We found something that swapped order with the moved section.
        if (sectionToSwapIndex > currentIndex) {

          for (int c = 0; c < current.getCount(); c++) {
            resultChangeSet.addChange(Change.move(getPreviousChildrenCount(currentChildrenList, key), swapToIndex));
          }

          // Place this section in the correct order in the current children list.
          currentChildrenList.remove(currentIndex);
          currentChildrenList.add(sectionToSwapIndex, current);
          for (int j = 0, size = currentChildrenList.size(); j < size; j++) {
            final Section section = currentChildrenList.get(j);
            final Pair<Section, Integer> valueAndIndex =
                currentChildren.get(section.getGlobalKey());

            if (valueAndIndex.second != j) {
             currentChildren.put(section.getGlobalKey(), new Pair<>(valueAndIndex.first, j));
            }
          }
        } else if (currentIndex > sectionToSwapIndex) { // We found something that was moved.
          sectionToSwapIndex = currentIndex;
          swapToIndex = getPreviousChildrenCount(currentChildrenList, key) +
              currentChildrenList.get(sectionToSwapIndex).getCount() - 1;
        }
      }
    }

    final SparseArray<ChangeSet> changeSets =
        generateChildrenChangeSets(
            sectionContext,
            currentChildren,
            newChildren,
            currentChildrenList,
            newChildrenList,
            removedComponents,
            sectionsDebugLogger,
            sectionTreeTag,
            updateCurrentPrefix,
            updateNewPrefix,
            thread);

    for (int i = 0, size = changeSets.size(); i < size; i++) {
      ChangeSet changeSet = changeSets.valueAt(i);
      resultChangeSet = ChangeSet.merge(resultChangeSet, changeSet);

      if (changeSet != null) {
        changeSet.release();
      }
    }

    releaseChangeSetSparseArray(changeSets);
    newRoot.setCount(resultChangeSet.getCount());

    return resultChangeSet;
  }

  /**
   * Generates a list of {@link ChangeSet} for the children of newRoot and currentRoot. The
   * generated SparseArray will contain an element for each children of currentRoot. {@link
   * ChangeSet}s for new items in newRoot will me merged in place with the appropriate {@link
   * ChangeSet}. If for example a new child is added in position 2, its {@link ChangeSet} will be
   * merged with the {@link ChangeSet} generated for the child of currentRoot in position 1. This
   * still guarantees a correct ordering while preserving the validity of indexes in the children of
   * currentRoot. Re-ordering a child is not supported and will trigger an {@link
   * IllegalStateException}.
   */
  private static SparseArray<ChangeSet> generateChildrenChangeSets(
      SectionContext sectionContext,
      Map<String, Pair<Section, Integer>> currentChildren,
      Map<String, Pair<Section, Integer>> newChildren,
      List<Section> currentChildrenList,
      List<Section> newChildrenList,
      List<Section> removedComponents,
      SectionsDebugLogger sectionsDebugLogger,
      String sectionTreeTag,
      String currentPrefix,
      String newPrefix,
      String thread) {
    final SparseArray<ChangeSet> changeSets = acquireChangeSetSparseArray();

    // Find removed current children.
    for (int i = 0; i < currentChildrenList.size(); i++) {
      final String key = currentChildrenList.get(i).getGlobalKey();
      final Section currentChild = currentChildrenList.get(i);

      if (newChildren.get(key) == null) {
        changeSets.put(
            i,
            generateChangeSetRecursive(
                sectionContext,
                currentChild,
                null,
                removedComponents,
                sectionsDebugLogger,
                sectionTreeTag,
                currentPrefix,
                newPrefix,
                thread));
      }
    }

    int activeChildIndex = 0;
    for (int i = 0; i < newChildrenList.size(); i++) {
      final Section newChild = newChildrenList.get(i);
      final Pair<Section, Integer> valueAndPosition = currentChildren.get(newChild.getGlobalKey());
      final int currentChildIndex = valueAndPosition != null ? valueAndPosition.second : -1;

      // New child was added.
      if (currentChildIndex < 0) {
        final ChangeSet currentChangeSet = changeSets.get(activeChildIndex);
        final ChangeSet changeSet =
            generateChangeSetRecursive(
                sectionContext,
                null,
                newChild,
                removedComponents,
                sectionsDebugLogger,
                sectionTreeTag,
                currentPrefix,
                newPrefix,
                thread);

        changeSets.put(activeChildIndex, ChangeSet.merge(currentChangeSet, changeSet));

        if (currentChangeSet != null) {
          currentChangeSet.release();
        }

        changeSet.release();
      } else {
        activeChildIndex = currentChildIndex;

        final ChangeSet currentChangeSet = changeSets.get(activeChildIndex);
        final ChangeSet changeSet =
            generateChangeSetRecursive(
                sectionContext,
                currentChildrenList.get(currentChildIndex),
                newChild,
                removedComponents,
                sectionsDebugLogger,
                sectionTreeTag,
                currentPrefix,
                newPrefix,
                thread);

        changeSets.put(activeChildIndex, ChangeSet.merge(currentChangeSet,changeSet));

        if (currentChangeSet != null) {
          currentChangeSet.release();
        }

        changeSet.release();
      }
    }

    releaseChildrenMap(currentChildren);
    releaseChildrenMap(newChildren);

    return changeSets;
  }

  private static SparseArray<ChangeSet> acquireChangeSetSparseArray() {
    //TODO use pools instead t11953296
    return new SparseArray<>();
  }

  private static void releaseChangeSetSparseArray(SparseArray<ChangeSet> changeSets) {
    //TODO use pools t11953296
  }

  private static ChangeSetState acquireChangeSetState() {
    //TODO use pools t11953296
    return new ChangeSetState();
  }

  /**
   * @return the ChangeSet that needs to be applied when transitioning from currentRoot to newRoot.
   */
  ChangeSet getChangeSet() {
    return mChangeSet;
  }

  /**
   * @return the {@link Section} that was used as current root for this ChangeSet computation.
   */
  Section getCurrentRoot() {
    return mCurrentRoot;
  }

  /**
   * @return the {@link Section} that was used as new root for this ChangeSet computation.
   */
  Section getNewRoot() {
    return mNewRoot;
  }

  /**
   * @return the {@link Section} that were removed from the tree as result of this ChangeSet
   * computation.
   */
  List<Section> getRemovedComponents() {
    return mRemovedComponents;
  }

  void release() {
    mRemovedComponents.clear();
    //TODO use pools t11953296
  }

  private final static int getPreviousChildrenCount(List<Section> sections, String key) {
    int count = 0;
    for (Section s : sections) {
      if (s.getGlobalKey().equals(key)) {
        return count;
      }

      count += s.getCount();
    }

    return count;
  }

  private final static String updatePrefix(Section root, String prefix) {
    if (root != null && root.getParent() == null) {
      return root.getClass().getSimpleName();
    } else if (root != null) {
      return prefix + "->" + root.getClass().getSimpleName();
    }
    return "";
  }
}

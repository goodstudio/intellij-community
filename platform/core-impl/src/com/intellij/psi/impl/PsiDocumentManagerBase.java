// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.psi.impl;

import com.intellij.codeInsight.multiverse.CodeInsightContext;
import com.intellij.codeInsight.multiverse.CodeInsightContexts;
import com.intellij.codeInsight.multiverse.FileViewProviderUtil;
import com.intellij.codeWithMe.ClientId;
import com.intellij.concurrency.ThreadContext;
import com.intellij.core.CoreBundle;
import com.intellij.injected.editor.DocumentWindow;
import com.intellij.lang.ASTNode;
import com.intellij.lang.injection.InjectedLanguageManager;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.*;
import com.intellij.openapi.application.ex.ApplicationManagerEx;
import com.intellij.openapi.diagnostic.Attachment;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.diagnostic.RuntimeExceptionWithAttachments;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.event.DocumentEvent;
import com.intellij.openapi.editor.event.DocumentListener;
import com.intellij.openapi.editor.ex.DocumentEx;
import com.intellij.openapi.editor.ex.PrioritizedDocumentListener;
import com.intellij.openapi.editor.impl.DocumentImpl;
import com.intellij.openapi.editor.impl.EditorDocumentPriorities;
import com.intellij.openapi.editor.impl.FrozenDocument;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileDocumentManagerListener;
import com.intellij.openapi.fileEditor.impl.FileDocumentManagerBase;
import com.intellij.openapi.progress.*;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.FileIndexFacade;
import com.intellij.openapi.util.*;
import com.intellij.openapi.util.registry.Registry;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.*;
import com.intellij.psi.impl.file.impl.FileManager;
import com.intellij.psi.impl.file.impl.FileManagerEx;
import com.intellij.psi.impl.file.impl.FileManagerImpl;
import com.intellij.psi.impl.smartPointers.SmartPointerManagerImpl;
import com.intellij.psi.impl.source.PsiFileImpl;
import com.intellij.psi.impl.source.tree.FileElement;
import com.intellij.psi.text.BlockSupport;
import com.intellij.psi.util.PsiUtilCore;
import com.intellij.util.*;
import com.intellij.util.concurrency.Semaphore;
import com.intellij.util.concurrency.ThreadingAssertions;
import com.intellij.util.containers.CollectionFactory;
import com.intellij.util.containers.ContainerUtil;
import com.intellij.util.ui.EdtInvocationManager;
import org.jetbrains.annotations.*;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;

public abstract class PsiDocumentManagerBase extends PsiDocumentManager implements DocumentListener, Disposable {
  private static final Logger LOG = Logger.getInstance(PsiDocumentManagerBase.class);
  private static final Key<Document> HARD_REF_TO_DOCUMENT = Key.create("HARD_REFERENCE_TO_DOCUMENT");

  private boolean isInsideCommitHandler; //accessed from EDT only

  private final Map<Document, List<Runnable>> documentCommitActions = CollectionFactory.createConcurrentWeakMap();
  private final Map<Object, Runnable> identifiedAllDocumentCommitActions = new LinkedHashMap<>(); //accessed from EDT only
  private final List<Runnable> allDocumentCommitActions = new ArrayList<>(); //accessed from EDT only

  @ApiStatus.Internal
  protected final Project myProject;
  private final PsiManager myPsiManager;
  private final DocumentCommitProcessor myDocumentCommitProcessor;

  @ApiStatus.Internal
  protected final Set<Document> myUncommittedDocuments = Collections.newSetFromMap(CollectionFactory.createConcurrentWeakMap());
  private final Map<Document, Throwable> myUncommittedDocumentTraces = CollectionFactory.createConcurrentWeakMap();
  private final Map<Document, UncommittedInfo> myUncommittedInfos = new ConcurrentHashMap<>();
  private /*non-static*/ final Key<UncommittedInfo> FREE_THREADED_UNCOMMITTED_INFO = Key.create("FREE_THREADED_UNCOMMITTED_INFO");

  @ApiStatus.Internal
  protected boolean myStopTrackingDocuments;
  private boolean myPerformBackgroundCommit = true;

  @SuppressWarnings("ThreadLocalNotStaticFinal")
  private final ThreadLocal<Integer> myIsCommitInProgress = new ThreadLocal<>();
  private static final ThreadLocal<Boolean> ourIsFullReparseInProgress = new ThreadLocal<>();
  private final PsiToDocumentSynchronizer mySynchronizer;

  private final List<Listener> myListeners = ContainerUtil.createLockFreeCopyOnWriteList();

  protected PsiDocumentManagerBase(@NotNull Project project) {
    myProject = project;
    myPsiManager = PsiManager.getInstance(project);
    myDocumentCommitProcessor = ApplicationManager.getApplication().getService(DocumentCommitProcessor.class);
    mySynchronizer = new PsiToDocumentSynchronizer(this, project.getMessageBus());
    project.getMessageBus().connect(this).subscribe(FileDocumentManagerListener.TOPIC, new FileDocumentManagerListener() {
      @Override
      public void fileContentLoaded(final @NotNull VirtualFile virtualFile, @NotNull Document document) {
        PsiFile psiFile = ReadAction.compute(() -> {
          // todo IJPL-339 figure out which psi file to pass here or get rid of psi file at all
          return myProject.isDisposed() || !virtualFile.isValid() ? null : getCachedPsiFile(virtualFile, CodeInsightContexts.anyContext());
        });
        fireDocumentCreated(document, psiFile);
      }
    });
  }

  @Override
  public @Nullable PsiFile getPsiFile(@NotNull Document document) {
    return getPsiFile(document, CodeInsightContexts.anyContext());
  }

  @ApiStatus.Internal
  @Override
  public @Nullable PsiFile getPsiFile(@NotNull Document document, @NotNull CodeInsightContext context) {
    if (document instanceof DocumentWindow && !((DocumentWindow)document).isValid()) {
      return null;
    }

    PsiFile psiFile = getCachedPsiFile(document, context);
    if (psiFile != null) {
      return ensureValidFile(psiFile, "Cached PSI");
    }

    VirtualFile virtualFile = FileDocumentManager.getInstance().getFile(document);
    if (virtualFile == null || !virtualFile.isValid()) return null;

    psiFile = getFileManager().findFile(virtualFile, context);
    if (psiFile == null) return null;

    fireFileCreated(document, psiFile);

    return psiFile;
  }

  private static @NotNull PsiFile ensureValidFile(@NotNull PsiFile psiFile, @NotNull @NonNls String debugInfo) {
    if (!psiFile.isValid()) throw new PsiInvalidElementAccessException(psiFile, debugInfo);
    return psiFile;
  }

  public void associatePsi(@NotNull Document document, @NotNull PsiFile file) {
    if (file.getProject() != myProject) {
      throw new IllegalArgumentException("Method associatePsi() called with file from the wrong project. Expected: "+myProject+" but got: "+file.getProject());
    }
    VirtualFile vFile = file.getViewProvider().getVirtualFile();
    Document cachedDocument = FileDocumentManager.getInstance().getCachedDocument(vFile);
    if (cachedDocument != null && cachedDocument != document) {
      throw new IllegalStateException("Can't replace existing document");
    }

    FileDocumentManagerBase.registerDocument(document, vFile);
  }

  @Override
  public final @Nullable PsiFile getCachedPsiFile(@NotNull Document document) {
    return getCachedPsiFile(document, CodeInsightContexts.anyContext());
  }

  @ApiStatus.Internal
  @Override
  public final PsiFile getCachedPsiFile(@NotNull Document document, @NotNull CodeInsightContext context) {
    VirtualFile virtualFile = FileDocumentManager.getInstance().getFile(document);
    return virtualFile == null || !virtualFile.isValid() ? null : getCachedPsiFile(virtualFile, context);
  }

  /**
   * @return associated psi file, it's it cached in {@link FileManagerImpl}.
   * It's guaranteed to not perform any expensive ops like creating files/reparse/resurrecting PsiFile from temp comatose state.
   */
  @ApiStatus.Internal
  public final @Nullable PsiFile getRawCachedFile(@NotNull VirtualFile virtualFile, @NotNull CodeInsightContext context) {
    FileManagerEx manager = ((FileManagerEx)getFileManager());
    return manager.getRawCachedFile(virtualFile, context);
  }

  @ApiStatus.Internal
  public final @NotNull List<FileViewProvider> getCachedViewProviders(@NotNull Document document) {
    VirtualFile virtualFile = getVirtualFile(document);
    if (virtualFile == null) return Collections.emptyList();
    return getFileManager().findCachedViewProviders(virtualFile);
  }

  private static @Nullable VirtualFile getVirtualFile(@NotNull Document document) {
    VirtualFile virtualFile = FileDocumentManager.getInstance().getFile(document);
    if (virtualFile == null || !virtualFile.isValid()) return null;
    return virtualFile;
  }

  private @Nullable PsiFile getCachedPsiFile(@NotNull VirtualFile virtualFile, @NotNull CodeInsightContext context) {
    return getFileManager().getCachedPsiFile(virtualFile, context);
  }

  private @NotNull FileManager getFileManager() {
    return ((PsiManagerEx)myPsiManager).getFileManager();
  }

  @Override
  public Document getDocument(@NotNull PsiFile psiFile) {
    Document document = getCachedDocument(psiFile);
    if (document != null) {
      if (!psiFile.getViewProvider().isPhysical()) {
        PsiUtilCore.ensureValid(psiFile);
        associatePsi(document, psiFile);
      }
      return document;
    }

    FileViewProvider viewProvider = psiFile.getViewProvider();
    if (!viewProvider.isEventSystemEnabled()) {
      return null;
    }

    VirtualFile virtualFile = viewProvider.getVirtualFile();
    document = FileDocumentManager.getInstance().getDocument(virtualFile, myProject);
    if (document != null) {
      if (document.getTextLength() != psiFile.getTextLength()) {
        // We have internal state inconsistency, it might be a good idea to contact the core team if you are able to reproduce this error.
        String message = "Document/PSI mismatch: " + psiFile + " of " + psiFile.getClass() +
                         "; viewProvider=" + viewProvider +
                         "; uncommitted=" + Arrays.toString(getUncommittedDocuments());
        String documentText = document.getText();
        String fileText;
        try {
          fileText = psiFile.getText();
        }
        catch (AssertionError e) {
          fileText = "file.getText() failed with an error: " + e;
        }
        throw new RuntimeExceptionWithAttachments(message,
                                                  new Attachment("document.txt", documentText),
                                                  new Attachment("psi.txt", fileText));
      }

      if (!viewProvider.isPhysical()) {
        PsiUtilCore.ensureValid(psiFile);
        associatePsi(document, psiFile);
        psiFile.putUserData(HARD_REF_TO_DOCUMENT, document);
      }
    }

    return document;
  }

  @Override
  public Document getCachedDocument(@NotNull PsiFile psiFile) {
    if (!psiFile.isPhysical()) return null;
    VirtualFile vFile = psiFile.getViewProvider().getVirtualFile();
    return FileDocumentManager.getInstance().getCachedDocument(vFile);
  }

  @Override
  public void commitAllDocuments() {
    ThreadingAssertions.assertEventDispatchThread();
    WriteIntentReadAction.run((Runnable)() -> {
      ((TransactionGuardImpl)TransactionGuard.getInstance()).assertWriteActionAllowed();

      if (myUncommittedDocuments.isEmpty()) return;

      Document[] documents = getUncommittedDocuments();
      for (Document document : documents) {
        if (isCommitted(document)) {
          if (!isEventSystemEnabled(document)) {
            // another thread has just committed it, everything's fine
            continue;
          }
          LOG.error("Committed document in uncommitted set: " + document);
        }
        if (!doCommit(document) && isEventSystemEnabled(document)) {
          LOG.error("Couldn't commit " + document);
        }
      }
      LOG.assertTrue(!hasEventSystemEnabledUncommittedDocuments(), myUncommittedDocuments);
    });
  }

  @Override
  public boolean commitAllDocumentsUnderProgress() {
    Application application = ApplicationManager.getApplication();
    if (application.isDispatchThread()) {
      if (application.isWriteAccessAllowed()) {
        commitAllDocuments();
        //there are lot of existing actions/processors/tests which execute it under write lock
        //do not show this message in unit test mode
        if (!application.isUnitTestMode()) {
          LOG.error("Do not call commitAllDocumentsUnderProgress inside write-action");
        }
        return true;
      }
      else if (application.isUnitTestMode()) {
        WriteAction.run(() -> commitAllDocuments());
        return true;
      }
    }
    final int semaphoreTimeoutInMs = 50;
    Runnable commitAllDocumentsRunnable = () -> {
      Semaphore semaphore = new Semaphore(1);
      AppUIExecutor.onWriteThread().later().submit(() -> {
        getInstance(myProject).performWhenAllCommitted(new Runnable() {
          @Override
          public void run() {
            semaphore.up();
          }

          @Override
          public String toString() {
            return "commitAllDocumentsUnderProgress()";
          }
        });
      });
      while (!semaphore.waitFor(semaphoreTimeoutInMs)) {
        ProgressManager.checkCanceled();
      }
    };
    return ProgressManager.getInstance().runProcessWithProgressSynchronously(commitAllDocumentsRunnable,
                                                                             CoreBundle.message("progress.title.processing.documents"),
                                                                             true, myProject);
  }

  @Override
  public void performForCommittedDocument(@NotNull Document doc, @NotNull Runnable action) {
    Document document = getTopLevelDocument(doc);
    if (isCommitted(document)) {
      action.run();
    }
    else {
      addRunOnCommit(document, action);
    }
  }

  /**
   * Cancel previously registered action and schedules (new) action to be executed when all documents are committed.
   *
   * @param key    the (unique) id of the action.
   * @param action The action to be executed after automatic commit.
   *               This action will overwrite any action which was registered under this key earlier.
   *               The action will be executed in EDT.
   * @return true if action has been run immediately, or false if action was scheduled for execution later.
   */
  public boolean cancelAndRunWhenAllCommitted(@NonNls @NotNull Object key, @NotNull Runnable action) {
    ThreadingAssertions.assertEventDispatchThread();
    if (myProject.isDisposed()) {
      action.run();
      return true;
    }
    if (!hasEventSystemEnabledUncommittedDocuments()) {
      if (!isCommitInProgress()) {
        // in case of fireWriteActionFinished() we didn't execute 'actionsWhenAllDocumentsAreCommitted' yet
        assert identifiedAllDocumentCommitActions.isEmpty() && allDocumentCommitActions.isEmpty() :
          identifiedAllDocumentCommitActions + "; " + allDocumentCommitActions + "; " +
          "uncommitted docs: " + StringUtil.join(
            myUncommittedDocuments,
            document -> document +
                        ":isEventSystemEnabled=" + isEventSystemEnabled(document) +
                        ":virtualFile=" + getVirtualFile(document),
            ","
          );
      }
      action.run();
      return true;
    }

    assertWeAreOutsideAfterCommitHandler();

    identifiedAllDocumentCommitActions.put(key, ClientId.decorateRunnable(action));
    return false;
  }

  @ApiStatus.Internal
  public void addRunOnCommit(@NotNull Document document, @NotNull Runnable action) {
    List<Runnable> actions = documentCommitActions.computeIfAbsent(document, __ -> ContainerUtil.createConcurrentList());
    actions.add(ThreadContext.captureThreadContext(action));
  }

  private @NotNull List<Runnable> getAndClearDocumentCommitActions(@NotNull Document document) {
    List<Runnable> list = documentCommitActions.remove(document);
    return list == null ? Collections.emptyList() : list;
  }

  private @NotNull List<Runnable> getAndClearAllDocumentCommitActions() {
    ThreadingAssertions.assertEventDispatchThread();

    List<Runnable> actions = new ArrayList<>();
    actions.addAll(identifiedAllDocumentCommitActions.values());
    actions.addAll(allDocumentCommitActions);
    identifiedAllDocumentCommitActions.clear();
    allDocumentCommitActions.clear();
    return actions;
  }

  @Override
  public void commitDocument(@NotNull Document doc) {
    Document document = getTopLevelDocument(doc);

    if (isEventSystemEnabled(document)) {
      ((TransactionGuardImpl)TransactionGuard.getInstance()).assertWriteActionAllowed();
    }

    if (!isCommitted(document)) {
      doCommit(document);
    }
  }

  @ApiStatus.Internal
  public boolean isEventSystemEnabled(@NotNull Document document) {
    return ReadAction.compute(() -> {
      List<FileViewProvider> viewProviders = getCachedViewProviders(document);
      return FileViewProviderUtil.isEventSystemEnabled(viewProviders);
    });
  }

  @ApiStatus.Internal
  public boolean finishCommit(@NotNull Document document,
                              @NotNull List<? extends BooleanRunnable> finishProcessors,
                              @NotNull List<? extends BooleanRunnable> reparseInjectedProcessors,
                              boolean synchronously,
                              @NotNull Object reason) {
    assert !myProject.isDisposed() : "Already disposed";
    if (isEventSystemEnabled(document)) {
      ThreadingAssertions.assertEventDispatchThread();
    }
    boolean[] ok = {true};
    if (synchronously) {
      ok[0] = finishCommitInWriteAction(document, finishProcessors, reparseInjectedProcessors, true);
    }
    else {
      ApplicationManager.getApplication().runWriteAction(() -> {
        ok[0] = finishCommitInWriteAction(document, finishProcessors, reparseInjectedProcessors, false);
      });
    }

    if (ok[0]) {
      // run after commit actions outside write action
      runAfterCommitActions(document);
      if (DebugUtil.DO_EXPENSIVE_CHECKS && !ApplicationManagerEx.isInStressTest()) {
        checkAllElementsValid(document, reason);
      }
    }
    return ok[0];
  }

  protected boolean finishCommitInWriteAction(@NotNull Document document,
                                              @NotNull List<? extends BooleanRunnable> finishProcessors,
                                              @NotNull List<? extends BooleanRunnable> reparseInjectedProcessors,
                                              boolean synchronously) {
    if (isEventSystemEnabled(document)) {
      ThreadingAssertions.assertEventDispatchThread();
    }
    if (myProject.isDisposed()) return false;
    assert !(document instanceof DocumentWindow);

    VirtualFile virtualFile = FileDocumentManager.getInstance().getFile(document);
    if (virtualFile != null) {
      getSmartPointerManager().fastenBelts(virtualFile);
    }

    List<FileViewProvider> viewProviders = getCachedViewProviders(document);

    AtomicBoolean success = new AtomicBoolean(true);
    executeInsideCommit(()-> {
      try {
        success.set(ProgressManager.getInstance().computeInNonCancelableSection(() -> {
          if (viewProviders.isEmpty()) {
            handleCommitWithoutPsi(document);
            return true;
          }
          return commitToExistingPsi(document, finishProcessors, reparseInjectedProcessors, synchronously, virtualFile);
        }));
      }
      catch (Throwable e) {
        try {
          forceReload(virtualFile, viewProviders);
        }
        finally {
          LOG.error("Exception while committing " + viewProviders + ", eventSystemEnabled=" + isEventSystemEnabled(document), e);
        }
      }
      finally {
        if (success.get()) {
          myUncommittedDocuments.remove(document);
          myUncommittedDocumentTraces.remove(document);
        }
      }
    });
    return success.get();
  }

  private boolean commitToExistingPsi(@NotNull Document document,
                                      @NotNull List<? extends BooleanRunnable> finishProcessors,
                                      @NotNull List<? extends BooleanRunnable> reparseInjectedProcessors,
                                      boolean synchronously,
                                      @Nullable VirtualFile virtualFile) {
    for (BooleanRunnable finishRunnable : finishProcessors) {
      boolean success = finishRunnable.run();
      if (synchronously) {
        assert success : finishRunnable + " in " + finishProcessors;
      }
      if (!success) {
        return false;
      }
    }
    clearUncommittedInfo(document);
    if (virtualFile != null) {
      getSmartPointerManager().updatePointerTargetsAfterReparse(virtualFile);
    }
    List<FileViewProvider> viewProviders = getCachedViewProviders(document);
    for (FileViewProvider viewProvider : viewProviders) {
      viewProvider.contentsSynchronized();
    }
    for (BooleanRunnable runnable : reparseInjectedProcessors) {
      if (!runnable.run()) return false;
    }
    return true;
  }

  @ApiStatus.Internal
  public void forceReload(@Nullable VirtualFile virtualFile, @NotNull List<? extends FileViewProvider> viewProviders) {
    if (!viewProviders.isEmpty()) {
      DebugUtil.performPsiModification("psi.forceReload", () -> {
        for (FileViewProvider viewProvider : viewProviders) {
          ((AbstractFileViewProvider)viewProvider).markInvalidated();
        }
      });
    }
    if (virtualFile != null) {
      ((FileManagerEx)getFileManager()).forceReload(virtualFile);
    }
  }

  private void checkAllElementsValid(@NotNull Document document, @NotNull Object reason) {
    PsiFile psiFile = getCachedPsiFile(document);
    if (psiFile != null) {
      psiFile.accept(new PsiRecursiveElementWalkingVisitor() {
        @Override
        public void visitElement(@NotNull PsiElement element) {
          if (!element.isValid()) {
            throw new AssertionError("Commit to '" + psiFile.getVirtualFile() + "' has led to invalid element: " + element + "; Reason: '" + reason + "'");
          }
        }
      });
    }
  }

  private boolean doCommit(@NotNull Document document) {
    assert !isCommitInProgress() : "Do not call commitDocument() from inside PSI change listener";

    // otherwise there are many clients calling commitAllDocs() on PSI childrenChanged()
    if (getSynchronizer().isDocumentAffectedByTransactions(document)) {
      return false;
    }

    PsiFile psiFile = getPsiFile(document);
    if (psiFile == null) {
      myUncommittedDocuments.remove(document);
      myUncommittedDocumentTraces.remove(document);
      runAfterCommitActions(document);
      return true; // the project must be closing or file deleted
    }

    if (ApplicationManager.getApplication().isDispatchThread()) {
      ApplicationManager.getApplication().runWriteAction(() -> doCommit(document, psiFile));
    }
    else {
      doCommit(document, psiFile);
    }

    return true;
  }

  private void doCommit(@NotNull Document document, @NotNull PsiFile psiFile) {
    assert !isCommitInProgress() : "Do not call commitDocument() from inside PSI change listener";
    executeInsideCommit(() -> myDocumentCommitProcessor.commitSynchronously(document, myProject, psiFile));
    assert !isInUncommittedSet(document) : "Document :" + document;
    runAfterCommitActions(document);
  }

  // true if the PSI is being modified and events being sent
  public boolean isCommitInProgress() {
    return myIsCommitInProgress.get() != null || isFullReparseInProgress();
  }

  // inside this method isCommitInProgress() == true
  private void executeInsideCommit(@NotNull Runnable runnable) {
    Integer counter = myIsCommitInProgress.get();
    myIsCommitInProgress.set(counter == null ? 1 : counter + 1);
    try {
      runnable.run();
    }
    finally {
      myIsCommitInProgress.set(counter);
    }
  }

  @ApiStatus.Internal
  public static boolean isFullReparseInProgress() {
    return ourIsFullReparseInProgress.get() == Boolean.TRUE;
  }

  @Override
  public <T> T commitAndRunReadAction(@NotNull Computable<T> computation) {
    Ref<T> ref = Ref.create(null);
    commitAndRunReadAction(() -> ref.set(computation.compute()));
    return ref.get();
  }

  @Override
  public void reparseFiles(@NotNull Collection<? extends VirtualFile> files, boolean includeOpenFiles) {
    FileContentUtilCore.reparseFiles(files);
  }

  @Override
  public void commitAndRunReadAction(@NotNull Runnable runnable) {
    Application application = ApplicationManager.getApplication();
    if (application.isDispatchThread()) {
      commitAllDocuments();
      runnable.run();
      return;
    }

    if (application.holdsReadLock()) {
      LOG.error("Don't call commitAndRunReadAction inside ReadAction, it will cause a deadlock. "+Thread.currentThread());
    }

    while (true) {
      boolean executed = ReadAction.compute(() -> {
        if (!hasEventSystemEnabledUncommittedDocuments()) {
          runnable.run();
          return true;
        }
        return false;
      });
      if (executed) break;

      ModalityState modality = ModalityState.defaultModalityState();
      Semaphore semaphore = new Semaphore(1);
      AppUIExecutor.onWriteThread(ModalityState.any()).submit(() -> {
        if (myProject.isDisposed()) {
          // committedness doesn't matter anymore; give clients a chance to do checkCanceled
          semaphore.up();
          return;
        }

        performWhenAllCommitted(modality, () -> semaphore.up());
      });

      while (!semaphore.waitFor(10)) {
        ProgressManager.checkCanceled();
      }
    }
  }

  /**
   * Schedules action to be executed when all documents are committed.
   *
   * @return true if action has been run immediately, or false if action was scheduled for execution later.
   */
  @Override
  public boolean performWhenAllCommitted(@NotNull Runnable action) {
    return performWhenAllCommitted(ModalityState.defaultModalityState(), action);
  }

  // return true when action is run, false when it's queued to run later
  private boolean performWhenAllCommitted(@NotNull ModalityState modality, @NotNull Runnable action) {
    ThreadingAssertions.assertEventDispatchThread();
    assertWeAreOutsideAfterCommitHandler();

    assert !myProject.isDisposed() : "Already disposed: " + myProject;
    if (!hasEventSystemEnabledUncommittedDocuments()) {
      action.run();
      return true;
    }

    allDocumentCommitActions.add(ThreadContext.captureThreadContext(action));

    if (modality != ModalityState.nonModal() && TransactionGuard.getInstance().isWriteSafeModality(modality)) {
      // this client obviously expects all documents to be committed ASAP even inside modal dialog
      for (Document document : myUncommittedDocuments) {
        try (AccessToken ignore = SlowOperations.knownIssue("IJPL-162971")) {
          retainProviderAndCommitAsync(document, "re-added because performWhenAllCommitted(" + modality + ") was called", modality);
        }
      }
    }
    return false;
  }

  @Override
  public void performLaterWhenAllCommitted(@NotNull Runnable runnable) {
    performLaterWhenAllCommitted(ModalityState.defaultModalityState(), runnable);
  }

  @Override
  public void performLaterWhenAllCommitted(@NotNull ModalityState modalityState, @NotNull Runnable runnable) {
    Runnable whenAllCommitted = new Runnable() {
      @Override
      public void run() {
        ApplicationManager.getApplication().invokeLater(() -> {
          if (PsiDocumentManagerBase.this.hasEventSystemEnabledUncommittedDocuments()) {
            // no luck, will try later
            PsiDocumentManagerBase.this.performLaterWhenAllCommitted(runnable);
          }
          else {
            runnable.run();
          }
        }, modalityState, myProject.getDisposed());
      }

      @Override
      public String toString() {
        return "performLaterWhenAllCommitted(" + runnable+ ")";
      }
    };
    if (ApplicationManager.getApplication().isDispatchThread() && isInsideCommitHandler()) {
      whenAllCommitted.run();
    }
    else {
      EdtInvocationManager.invokeLaterIfNeeded(() -> {
        if (!myProject.isDisposed()) {
          performWhenAllCommitted(whenAllCommitted);
        }
      });
    }
  }

  private void runAfterCommitActions(@NotNull Document document) {
    Application app = ApplicationManager.getApplication();
    if (!app.isDispatchThread() && isEventSystemEnabled(document)) {
      // have to run in EDT to guarantee data structure safe access and "execute in EDT" callbacks contract
      app.invokeLater(()-> {
        if (!myProject.isDisposed() && isCommitted(document)) {
          runAfterCommitActions(document);
        }
      });
      return;
    }

    runActions(getAndClearDocumentCommitActions(document));

    if (app.isDispatchThread()) {
      runActionsWhenAllCommitted();
    }
    else if (isEventSystemEnabled(document)) {
      app.invokeLater(() -> runActionsWhenAllCommitted(), myProject.getDisposed());
    }
  }

  private void runActionsWhenAllCommitted() {
    ThreadingAssertions.assertEventDispatchThread();
    if (!mayRunActionsWhenAllCommitted()) return;

    // block adding listeners
    isInsideCommitHandler = true;

    try {
      runActions(getAndClearAllDocumentCommitActions());
    }
    finally {
      // unblock adding listeners
      isInsideCommitHandler = false;
    }
  }

  private static void runActions(@NotNull List<? extends Runnable> actions) {
    List<Pair<Runnable, Throwable>> exceptions = new ArrayList<>();
    for (Runnable action : actions) {
      //noinspection IncorrectCancellationExceptionHandling
      try {
        ThreadContext.resetThreadContext(() -> {
          action.run();
          return null;
        });
      }
      catch (ProcessCanceledException e) {
        // some actions are crazy enough to use PCE for their own control flow.
        // swallow and ignore to not disrupt completely unrelated control flow.
      }
      catch (Throwable e) {
        exceptions.add(Pair.create(action, e));
      }
    }
    for (Pair<Runnable, Throwable> pair : exceptions) {
      Runnable action = pair.getFirst();
      Throwable e = pair.getSecond();
      LOG.error("During running " + action, e);
    }
  }

  private boolean mayRunActionsWhenAllCommitted() {
    ThreadingAssertions.assertEventDispatchThread();
    return !isCommitInProgress() &&
           !(identifiedAllDocumentCommitActions.isEmpty() && allDocumentCommitActions.isEmpty()) &&
           !hasEventSystemEnabledUncommittedDocuments();
  }

  @Override
  public boolean hasEventSystemEnabledUncommittedDocuments() {
    try (AccessToken ignore = SlowOperations.knownIssue("IDEA-319884, EA-831652, IDEA-301732, EA-659436, IDEA-307614, EA-773260")) {
      return ContainerUtil.exists(myUncommittedDocuments, this::isEventSystemEnabled);
    }
  }

  private void assertWeAreOutsideAfterCommitHandler() {
    if (isInsideCommitHandler()) {
      throw new IncorrectOperationException("You must not call performWhenAllCommitted()/cancelAndRunWhenCommitted() from within after-commit handler");
    }
  }

  private boolean isInsideCommitHandler() {
    ThreadingAssertions.assertEventDispatchThread();
    return isInsideCommitHandler;
  }

  @Override
  public boolean isDocumentBlockedByPsi(@NotNull Document document) {
    return false;
  }

  @Override
  public void doPostponedOperationsAndUnblockDocument(@NotNull Document document) {
  }

  private void fireDocumentCreated(@NotNull Document document, PsiFile file) {
    myProject.getMessageBus().syncPublisher(PsiDocumentListener.TOPIC).documentCreated(document, file, myProject);
    for (Listener listener : myListeners) {
      listener.documentCreated(document, file);
    }
  }

  private void fireFileCreated(@NotNull Document document, @NotNull PsiFile file) {
    myProject.getMessageBus().syncPublisher(PsiDocumentListener.TOPIC).fileCreated(file, document);
    for (Listener listener : myListeners) {
      listener.fileCreated(file, document);
    }
  }

  @Override
  public @NotNull CharSequence getLastCommittedText(@NotNull Document document) {
    return getLastCommittedDocument(document).getImmutableCharSequence();
  }

  @Override
  public long getLastCommittedStamp(@NotNull Document document) {
    return getLastCommittedDocument(getTopLevelDocument(document)).getModificationStamp();
  }

  @Override
  public @Nullable Document getLastCommittedDocument(@NotNull PsiFile psiFile) {
    Document document = getDocument(psiFile);
    return document == null ? null : getLastCommittedDocument(document);
  }

  public @NotNull DocumentEx getLastCommittedDocument(@NotNull Document document) {
    if (document instanceof FrozenDocument) return (DocumentEx)document;

    if (document instanceof DocumentWindow) {
      DocumentWindow window = (DocumentWindow)document;
      Document delegate = window.getDelegate();
      if (delegate instanceof FrozenDocument) return (DocumentEx)window;

      if (!window.isValid()) {
        throw new AssertionError("host committed: " + isCommitted(delegate) + ", window=" + window);
      }

      UncommittedInfo info = getUncommittedInfo(delegate);
      DocumentWindow answer = info == null ? null : info.myFrozenWindows.get(document);
      if (answer == null) answer = freezeWindow(window);
      if (info != null) answer = ConcurrencyUtil.cacheOrGet(info.myFrozenWindows, window, answer);
      return (DocumentEx)answer;
    }

    assert document instanceof DocumentImpl;
    UncommittedInfo info = getUncommittedInfo(document);
    return info != null ? info.myFrozen : ((DocumentImpl)document).freeze();
  }

  private @Nullable UncommittedInfo getUncommittedInfo(@NotNull Document document) {
    UncommittedInfo info = myUncommittedInfos.get(document);
    return info != null ? info : document.getUserData(FREE_THREADED_UNCOMMITTED_INFO);
  }

  private void associateUncommittedInfo(Document document, UncommittedInfo info) {
    if (isEventSystemEnabled(document)) {
      myUncommittedInfos.put(document, info);
    }
    else {
      document.putUserData(FREE_THREADED_UNCOMMITTED_INFO, info);
    }
  }

  protected @NotNull DocumentWindow freezeWindow(@NotNull DocumentWindow document) {
    throw new UnsupportedOperationException();
  }

  public @NotNull List<DocumentEvent> getEventsSinceCommit(@NotNull Document document) {
    assert document instanceof DocumentImpl : document;
    UncommittedInfo info = getUncommittedInfo(document);
    if (info != null) {
      return new ArrayList<>(info.myEvents);
    }
    return Collections.emptyList();
  }

  @Override
  public @NotNull Document @NotNull [] getUncommittedDocuments() {
    ApplicationManager.getApplication().assertReadAccessAllowed();
    if (myUncommittedDocuments.isEmpty()) {
      // myUncommittedDocuments is ConcurrentRefHashMap, so default toArray iterates it twice, even if collection is empty
      // (which is a common case during batch code analysis)
      return Document.EMPTY_ARRAY;
    }
    Document[] documents = myUncommittedDocuments.toArray(Document.EMPTY_ARRAY);
    return ArrayUtil.stripTrailingNulls(documents);
  }

  @ApiStatus.Internal
  public @NotNull Map<Document, Throwable> getUncommitedDocumentsWithTraces() {
    return Collections.unmodifiableMap(myUncommittedDocumentTraces);
  }

  @ApiStatus.Internal
  public boolean isInUncommittedSet(@NotNull Document document) {
    return myUncommittedDocuments.contains(getTopLevelDocument(document));
  }

  @Override
  public boolean isUncommited(@NotNull Document document) {
    return !isCommitted(document);
  }

  @Override
  public boolean isCommitted(@NotNull Document document) {
    document = getTopLevelDocument(document);
    if (getSynchronizer().isInSynchronization(document)) return true;
    return (!(document instanceof DocumentEx) || !((DocumentEx)document).isInEventsHandling())
           && !isInUncommittedSet(document);
  }

  public static @NotNull Document getTopLevelDocument(@NotNull Document document) {
    return document instanceof DocumentWindow ? ((DocumentWindow)document).getDelegate() : document;
  }

  @Override
  public boolean hasUncommitedDocuments() {
    return !isCommitInProgress() && !myUncommittedDocuments.isEmpty();
  }

  @Override
  public void beforeDocumentChange(@NotNull DocumentEvent event) {
    if (myStopTrackingDocuments || myProject.isDisposed()) return;

    Document document = event.getDocument();
    VirtualFile virtualFile = FileDocumentManager.getInstance().getFile(document);
    boolean isRelevant = virtualFile != null && isRelevant(virtualFile);

    if (document instanceof DocumentImpl && getUncommittedInfo(document) == null) {
      associateUncommittedInfo(document, new UncommittedInfo((DocumentImpl)document));
    }

    List<FileViewProvider> viewProviders = getCachedViewProviders(document);
    for (FileViewProvider viewProvider : viewProviders) {
      boolean inMyProject = viewProvider.getManager() == myPsiManager;
      if (!isRelevant || !inMyProject) {
        return;
      }

      List<PsiFile> files = viewProvider.getAllFiles();
      PsiFile psiCause = null;
      for (PsiFile file : files) {
        if (file == null) {
          throw new AssertionError("View provider " + viewProvider +
                                   " (" + viewProvider.getClass() + ") returned null in its files array: " +
                                   files + " for file " +
                                   viewProvider.getVirtualFile());
        }

        if (PsiToDocumentSynchronizer.isInsideAtomicChange(file)) {
          psiCause = file;
        }
      }

      if (psiCause == null) {
        beforeDocumentChangeOnUnlockedDocument(viewProvider);
      }
    }
  }

  protected void beforeDocumentChangeOnUnlockedDocument(@NotNull FileViewProvider viewProvider) {
  }

  @Override
  public void documentChanged(@NotNull DocumentEvent event) {
    if (myStopTrackingDocuments || myProject.isDisposed()) {
      return;
    }

    Document document = event.getDocument();

    VirtualFile virtualFile = FileDocumentManager.getInstance().getFile(document);
    boolean isRelevant = virtualFile != null && isRelevant(virtualFile);

    List<FileViewProvider> viewProviders = getCachedViewProviders(document);
    if (viewProviders.isEmpty()) {
      handleCommitWithoutPsi(document);
      return;
    }

    // todo IJPL-339 is this check correct at all? How can we get view providers from an alien project here???
    boolean inMyProject = ContainerUtil.exists(viewProviders, viewProvider -> viewProvider.getManager() == myPsiManager);
    if (!isRelevant || !inMyProject) {
      clearUncommittedInfo(document);
      return;
    }


    List<PsiFile> files = ContainerUtil.flatMap(viewProviders, p -> p.getAllFiles());
    if (files.isEmpty()) {
      handleCommitWithoutPsi(document);
      return;
    }

    boolean commitNecessary =
      !ContainerUtil.exists(files, file -> PsiToDocumentSynchronizer.isInsideAtomicChange(file) || !(file instanceof PsiFileImpl));

    Application application = ApplicationManager.getApplication();
    boolean forceCommit = ExternalChangeActionUtil.isExternalChangeInProgress() &&
                          (SystemProperties.getBooleanProperty("idea.force.commit.on.external.change", false) ||
                           application.isHeadlessEnvironment() && !application.isUnitTestMode());

    // Consider that it's worth to perform complete re-parse instead of merge if the whole document text is replaced and
    // current document lines number is roughly above 5000. This makes sense in situations when external change is performed
    // for the huge file (that causes the whole document to be reloaded and 'merge' way takes a while to complete).
    if (event.isWholeTextReplaced() && document.getTextLength() > 100000) {
      document.putUserData(BlockSupport.DO_NOT_REPARSE_INCREMENTALLY, Boolean.TRUE);
    }

    if (commitNecessary) {
      assert !(document instanceof DocumentWindow);
      myUncommittedDocuments.add(document);
      if (Registry.is("ide.activity.tracking.enable.debug")) {
        myUncommittedDocumentTraces.put(document, new Throwable());
      }
      if (forceCommit) {
        commitDocument(document);
      }
      else if (!document.isInBulkUpdate() && myPerformBackgroundCommit) {
        retainProviderAndCommitAsync(document, event, ModalityState.defaultModalityState());
      }
    }
    else {
      clearUncommittedInfo(document);
    }
  }

  @Override
  public void bulkUpdateStarting(@NotNull Document document) {
    document.putUserData(BlockSupport.DO_NOT_REPARSE_INCREMENTALLY, Boolean.TRUE);
  }

  @Override
  public void bulkUpdateFinished(@NotNull Document document) {
    retainProviderAndCommitAsync(document, "Bulk update finished", ModalityState.defaultModalityState());
  }

  private void retainProviderAndCommitAsync(@NotNull Document document,
                                            @NotNull Object reason,
                                            @NotNull ModalityState modality) {
    List<FileViewProvider> viewProviders = getCachedViewProviders(document);
    if (FileViewProviderUtil.isEventSystemEnabled(viewProviders)) {
      ThreadingAssertions.assertEventDispatchThread();
      // make cached provider non-gcable temporarily (until commit end) to avoid surprising getCachedProvider()==null
      myDocumentCommitProcessor.commitAsynchronously(myProject, this, document, reason, modality, viewProviders);
    }
  }

  @ApiStatus.Internal
  public class PriorityEventCollector implements PrioritizedDocumentListener {
    @Override
    public int getPriority() {
      return EditorDocumentPriorities.RANGE_MARKER;
    }

    @Override
    public void documentChanged(@NotNull DocumentEvent event) {
      UncommittedInfo info = getUncommittedInfo(event.getDocument());
      if (info != null) {
        info.myEvents.add(event);
      }
    }
  }

  @ApiStatus.Internal
  public void handleCommitWithoutPsi(@NotNull Document document) {
    UncommittedInfo prevInfo = clearUncommittedInfo(document);
    if (prevInfo == null) {
      return;
    }

    myUncommittedDocuments.remove(document);
    myUncommittedDocumentTraces.remove(document);

    if (!myProject.isInitialized() || myProject.isDisposed() || myProject.isDefault()) {
      return;
    }

    VirtualFile virtualFile = FileDocumentManager.getInstance().getFile(document);
    if (virtualFile != null) {
      FileManager fileManager = getFileManager();
      FileViewProvider viewProvider = fileManager.findCachedViewProvider(virtualFile);
      if (viewProvider != null) {
        // we can end up outside write action here if the document has forUseInNonAWTThread=true
        ApplicationManager.getApplication().runWriteAction(ExternalChangeActionUtil.externalChangeAction(() ->
                                                                                                           ((AbstractFileViewProvider)viewProvider).onContentReload()));
      }
      else if (FileIndexFacade.getInstance(myProject).isInContent(virtualFile)) {
        ApplicationManager.getApplication().runWriteAction(ExternalChangeActionUtil.externalChangeAction(() ->
                                                                                                           ((FileManagerEx)fileManager).firePropertyChangedForUnloadedPsi()));
      }
    }

    runAfterCommitActions(document);
  }

  private @Nullable UncommittedInfo clearUncommittedInfo(@NotNull Document document) {
    UncommittedInfo info = getUncommittedInfo(document);
    if (info != null) {
      myUncommittedInfos.remove(document);
      document.putUserData(FREE_THREADED_UNCOMMITTED_INFO, null);
      getSmartPointerManager().updatePointers(document, info.myFrozen, info.myEvents);
    }
    return info;
  }

  private SmartPointerManagerImpl getSmartPointerManager() {
    return (SmartPointerManagerImpl)SmartPointerManager.getInstance(myProject);
  }

  private boolean isRelevant(@NotNull VirtualFile virtualFile) {
    return !myProject.isDisposed() && !virtualFile.getFileType().isBinary();
  }

  public static boolean checkConsistency(@NotNull PsiFile psiFile, @NotNull Document document) {
    //todo hack
    if (psiFile.getVirtualFile() == null) return true;

    CharSequence editorText = document.getCharsSequence();
    int documentLength = document.getTextLength();
    if (psiFile.textMatches(editorText)) {
      LOG.assertTrue(psiFile.getTextLength() == documentLength);
      return true;
    }

    char[] fileText = psiFile.textToCharArray();
    @SuppressWarnings("NonConstantStringShouldBeStringBuffer")
    @NonNls String error = "File '" + psiFile.getName() + "' text mismatch after reparse. " +
                           "File length=" + fileText.length + "; Doc length=" + documentLength + "\n";
    int i = 0;
    for (; i < documentLength; i++) {
      if (i >= fileText.length) {
        error += "editorText.length > psiText.length i=" + i + "\n";
        break;
      }
      if (i >= editorText.length()) {
        error += "editorText.length > psiText.length i=" + i + "\n";
        break;
      }
      if (editorText.charAt(i) != fileText[i]) {
        error += "first unequal char i=" + i + "\n";
        break;
      }
    }
    //error += "*********************************************" + "\n";
    //if (i <= 500){
    //  error += "Equal part:" + editorText.subSequence(0, i) + "\n";
    //}
    //else{
    //  error += "Equal part start:\n" + editorText.subSequence(0, 200) + "\n";
    //  error += "................................................" + "\n";
    //  error += "................................................" + "\n";
    //  error += "................................................" + "\n";
    //  error += "Equal part end:\n" + editorText.subSequence(i - 200, i) + "\n";
    //}
    error += "*********************************************" + "\n";
    error += "Editor Text tail:(" + (documentLength - i) + ")\n";// + editorText.subSequence(i, Math.min(i + 300, documentLength)) + "\n";
    error += "*********************************************" + "\n";
    error += "Psi Text tail:(" + (fileText.length - i) + ")\n";
    error += "*********************************************" + "\n";

    if (document instanceof DocumentWindow) {
      error += "doc: '" + document.getText() + "'\n";
      error += "psi: '" + psiFile.getText() + "'\n";
      error += "ast: '" + psiFile.getNode().getText() + "'\n";
      error += psiFile.getLanguage() + "\n";
      PsiElement context = InjectedLanguageManager.getInstance(psiFile.getProject()).getInjectionHost(psiFile);
      if (context != null) {
        error += "context: " + context + "; text: '" + context.getText() + "'\n";
        error += "context file: " + context.getContainingFile() + "\n";
      }
      error += "document window ranges: " + Arrays.asList(((DocumentWindow)document).getHostRanges()) + "\n";
    }
    LOG.error(error);
    //document.replaceString(0, documentLength, psiFile.getText());
    return false;
  }

  @TestOnly
  public void clearUncommittedDocuments() {
    myUncommittedInfos.clear();
    myUncommittedDocuments.clear();
    myUncommittedDocumentTraces.clear();
    mySynchronizer.cleanupForNextTest();
  }

  @TestOnly
  public void disableBackgroundCommit(@NotNull Disposable parentDisposable) {
    assert myPerformBackgroundCommit;
    myPerformBackgroundCommit = false;
    Disposer.register(parentDisposable, () -> myPerformBackgroundCommit = true);
  }

  @Override
  public void dispose() {}

  public @NotNull PsiToDocumentSynchronizer getSynchronizer() {
    return mySynchronizer;
  }

  public void reparseFileFromText(@NotNull PsiFileImpl file) {
    if (isCommitInProgress()) throw new IllegalStateException("Re-entrant commit is not allowed");

    FileElement node = file.calcTreeElement();
    CharSequence text = node.getChars();
    ourIsFullReparseInProgress.set(Boolean.TRUE);
    try {
      ProgressIndicator indicator = EmptyProgressIndicator.notNullize(ProgressIndicatorProvider.getGlobalProgressIndicator());
      DiffLog log = BlockSupportImpl.makeFullParse(file, node, text, indicator, text).log;
      log.doActualPsiChange(file);
      file.getViewProvider().contentsSynchronized();
    }
    finally {
      ourIsFullReparseInProgress.remove();
    }
  }

  private static final class UncommittedInfo {
    private final FrozenDocument myFrozen;
    private final List<DocumentEvent> myEvents = new ArrayList<>();
    private final ConcurrentMap<DocumentWindow, DocumentWindow> myFrozenWindows = new ConcurrentHashMap<>();

    private UncommittedInfo(@NotNull DocumentImpl original) {
      myFrozen = original.freeze();
    }
  }

  @NotNull
  @ApiStatus.Internal
  public List<BooleanRunnable> reparseChangedInjectedFragments(@NotNull Document hostDocument,
                                                               @NotNull PsiFile hostPsiFile,
                                                               @NotNull TextRange range,
                                                               @NotNull ProgressIndicator indicator,
                                                               @NotNull ASTNode oldRoot,
                                                               @NotNull ASTNode newRoot) {
    return Collections.emptyList();
  }

  @TestOnly
  public boolean isDefaultProject() {
    return myProject.isDefault();
  }

  public @NonNls String someDocumentDebugInfo(@NotNull Document document) {
    @NotNull List<FileViewProvider> viewProviders = getCachedViewProviders(document);
    return "cachedProvider: " + viewProviders +
           "; isEventSystemEnabled: " + isEventSystemEnabled(document) +
           "; isCommitted:"+isCommitted(document)+
           "; myIsCommitInProgress:"+isCommitInProgress()+
           "; isInUncommittedSet:"+isInUncommittedSet(document);
  }

  /**
   * Try to find the project the {@code virtualFile} belongs to (from the directory structure the file located in) and make sure it's the same as {@link #myProject}
   */
  @ApiStatus.Internal
  public void assertFileIsFromCorrectProject(@NotNull VirtualFile virtualFile) {}
}

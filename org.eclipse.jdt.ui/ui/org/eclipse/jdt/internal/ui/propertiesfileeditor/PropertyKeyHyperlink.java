/*******************************************************************************
 * Copyright (c) 2000, 2005 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package org.eclipse.jdt.internal.ui.propertiesfileeditor;

import java.lang.reflect.InvocationTargetException;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IAdaptable;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.PlatformObject;
import org.eclipse.core.runtime.Status;

import org.eclipse.core.filebuffers.FileBuffers;
import org.eclipse.core.filebuffers.ITextFileBuffer;
import org.eclipse.core.filebuffers.ITextFileBufferManager;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IResourceProxy;
import org.eclipse.core.resources.IStorage;

import org.eclipse.swt.widgets.Shell;

import org.eclipse.jface.dialogs.ErrorDialog;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.operation.IRunnableWithProgress;
import org.eclipse.jface.resource.ImageDescriptor;
import org.eclipse.jface.viewers.ILabelProvider;
import org.eclipse.jface.window.Window;

import org.eclipse.jface.text.Assert;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.IRegion;
import org.eclipse.jface.text.hyperlink.IHyperlink;

import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IStorageEditorInput;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.dialogs.TwoPaneElementSelector;
import org.eclipse.ui.model.IWorkbenchAdapter;
import org.eclipse.ui.model.WorkbenchLabelProvider;
import org.eclipse.ui.texteditor.IEditorStatusLine;
import org.eclipse.ui.texteditor.ITextEditor;

import org.eclipse.search.internal.core.SearchScope;
import org.eclipse.search.internal.core.text.ITextSearchResultCollector;
import org.eclipse.search.internal.core.text.MatchLocator;
import org.eclipse.search.internal.core.text.TextSearchEngine;

import org.eclipse.jdt.core.JavaModelException;

import org.eclipse.jdt.internal.corext.util.Messages;

import org.eclipse.jdt.ui.JavaUI;

import org.eclipse.jdt.internal.ui.IJavaStatusConstants;
import org.eclipse.jdt.internal.ui.JavaPlugin;
import org.eclipse.jdt.internal.ui.javaeditor.EditorUtility;
import org.eclipse.jdt.internal.ui.util.ExceptionHandler;


/**
 * Properties key hyperlink.
 * <p>
 * XXX:	This does not work for properties files coming from a JAR due to
 * 		missing J Core functionality. For details see:
 * 		https://bugs.eclipse.org/bugs/show_bug.cgi?id=22376
 * </p>
 *
 * @since 3.1
 */
public class PropertyKeyHyperlink implements IHyperlink {


	private static class KeyReference extends PlatformObject implements IWorkbenchAdapter, Comparable {

		private static final Collator fgCollator= Collator.getInstance();

		private IStorage storage;
		private int offset;
		private int length;


		private KeyReference(IStorage storage, int offset, int length) {
			Assert.isNotNull(storage);
			this.storage= storage;
			this.offset= offset;
			this.length= length;
		}

		/*
		 * @see org.eclipse.core.runtime.IAdaptable#getAdapter(java.lang.Class)
		 */
		public Object getAdapter(Class adapter) {
			if (adapter == IWorkbenchAdapter.class)
				return this;
			else
				return super.getAdapter(adapter);
		}
		/*
		 * @see org.eclipse.ui.model.IWorkbenchAdapter#getChildren(java.lang.Object)
		 */
		public Object[] getChildren(Object o) {
			return null;
		}
		/*
		 * @see org.eclipse.ui.model.IWorkbenchAdapter#getImageDescriptor(java.lang.Object)
		 */
		public ImageDescriptor getImageDescriptor(Object object) {
			IWorkbenchAdapter wbAdapter= (IWorkbenchAdapter)storage.getAdapter(IWorkbenchAdapter.class);
			if (wbAdapter != null)
				return wbAdapter.getImageDescriptor(storage);
			return null;
		}
		/*
		 * @see org.eclipse.ui.model.IWorkbenchAdapter#getLabel(java.lang.Object)
		 */
		public String getLabel(Object o) {

			ITextFileBufferManager manager= FileBuffers.getTextFileBufferManager();
			try {
				manager.connect(storage.getFullPath(), null);
				try {
					ITextFileBuffer buffer= manager.getTextFileBuffer(storage.getFullPath());
					IDocument document= buffer.getDocument();
					if (document != null) {
						int line= document.getLineOfOffset(offset) + 1;
						Object[] args= new Object[] { new Integer(line), storage.getFullPath() };
						return Messages.format(PropertiesFileEditorMessages.OpenAction_SelectionDialog_elementLabel, args);
					}
				} finally {
					manager.disconnect(storage.getFullPath(), null);
				}
			} catch (CoreException e) {
				JavaPlugin.log(e.getStatus());
			} catch (BadLocationException e) {
				JavaPlugin.log(e);
			}

			return storage.getFullPath().toString();
		}
		/*
		 * @see org.eclipse.ui.model.IWorkbenchAdapter#getParent(java.lang.Object)
		 */
		public Object getParent(Object o) {
			return null;
		}

		public int compareTo(Object o) {
			KeyReference otherRef= (KeyReference)o;
			String thisPath= storage.getFullPath().toString();
			String otherPath= otherRef.storage.getFullPath().toString();
			int result= fgCollator.compare(thisPath, otherPath);
			if (result != 0)
				return result;
			else
				return offset - otherRef.offset;
		}
	}


	private static class ResultCollector implements ITextSearchResultCollector {

		private List<KeyReference> fResult;
		private IProgressMonitor fProgressMonitor;
		private boolean fIsKeyDoubleQuoted;

		public ResultCollector(List<KeyReference> result, IProgressMonitor progressMonitor, boolean isKeyDoubleQuoted) {
			fResult= result;
			fProgressMonitor= progressMonitor;
			fIsKeyDoubleQuoted= isKeyDoubleQuoted;
		}

		public void aboutToStart() throws CoreException {
			// do nothing;
		}

		public void accept(IResourceProxy proxy, int start, int length) throws CoreException {
			// Can cast to IFile because search only reports matches on IFile
			if (fIsKeyDoubleQuoted) {
				start= start + 1;
				length= length - 2;
			}
			fResult.add(new KeyReference((IFile)proxy.requestResource(), start, length));
		}

		public void done() throws CoreException {
			// do nothing;
		}

		public IProgressMonitor getProgressMonitor() {
			return fProgressMonitor;
		}
	}


	private IRegion fRegion;
	private String fPropertiesKey;
	private Shell fShell;
	private IStorage fStorage;
	private ITextEditor fEditor;


	/**
	 * Creates a new properties key hyperlink.
	 *
	 * @param region the region
	 * @param key the properties key
	 * @param editor the text editor
	 */
	public PropertyKeyHyperlink(IRegion region, String key, ITextEditor editor) {
		Assert.isNotNull(region);
		Assert.isNotNull(key);
		Assert.isNotNull(editor);

		fRegion= region;
		fPropertiesKey= key;
		fEditor= editor;
		IStorageEditorInput storageEditorInput= (IStorageEditorInput)fEditor.getEditorInput();
		fShell= fEditor.getEditorSite().getShell();
		try {
			fStorage= storageEditorInput.getStorage();
		} catch (CoreException e) {
			fStorage= null;
		}
	}

	/*
	 * @see org.eclipse.jdt.internal.ui.javaeditor.IHyperlink#getHyperlinkRegion()
	 */
	public IRegion getHyperlinkRegion() {
		return fRegion;
	}

	/*
	 * @see org.eclipse.jdt.internal.ui.javaeditor.IHyperlink#open()
	 */
	public void open() {
		if (!checkEnabled())
			return;

		// Search the key
		IResource resource= (IResource)fStorage;
		KeyReference[] references= null;
		if (resource != null)
			references= search(resource.getProject(), fPropertiesKey);

		if (references == null)
			return; // canceled by the user

		if (references.length == 0) {
			String message= PropertiesFileEditorMessages.OpenAction_error_messageNoResult;
			showErrorInStatusLine(message);
			return;
		}

		open(references);

	}

	private boolean checkEnabled() {
		 // XXX: Can be removed once support for JARs is available (see class Javadoc for details)
		return fStorage instanceof IResource;
	}

	private void open(KeyReference[] keyReferences) {
		Assert.isLegal(keyReferences != null && keyReferences.length > 0);

		if (keyReferences.length == 1)
			open(keyReferences[0]);
		else
			open(select(keyReferences));
	}

	/**
	 * Opens a dialog which allows to select a key reference.
	 * <p>
	 * FIXME: The lower pane is currently not sorted due to https://bugs.eclipse.org/bugs/show_bug.cgi?id=84220
	 * </p>
	 *
	 * @param keyReferences the array of key references
	 * @return the selected key reference or <code>null</code> if canceled by the user
	 */
	private KeyReference select(final KeyReference[] keyReferences) {
		Arrays.sort(keyReferences);
		final int length= keyReferences.length;
		ILabelProvider labelProvider= new WorkbenchLabelProvider() {
			public String decorateText(String input, Object element) {
				KeyReference keyRef= (KeyReference)element;
				IStorage storage= keyRef.storage;
				String name= storage.getName();
				if (name == null)
					return input;

				int count= 0;
				for (int i= 0; i < length; i++) {
					if (keyReferences[i].storage.equals(storage))
						count++;
				}
				if (count > 1) {
					Object[] args= new Object[] { name, new Integer(count) };
					name= Messages.format(PropertiesFileEditorMessages.OpenAction_SelectionDialog_elementLabelWithMatchCount, args);
				}

				return name;
			}
		};

		TwoPaneElementSelector dialog= new TwoPaneElementSelector(fShell, labelProvider, new WorkbenchLabelProvider());
		dialog.setLowerListLabel(PropertiesFileEditorMessages.OpenAction_SelectionDialog_details);
		dialog.setMultipleSelection(false);
		dialog.setTitle(PropertiesFileEditorMessages.OpenAction_SelectionDialog_title);
		dialog.setMessage(PropertiesFileEditorMessages.OpenAction_SelectionDialog_message);
		dialog.setElements(keyReferences);

		if (dialog.open() == Window.OK) {
			Object[] result= dialog.getResult();
			if (result != null && result.length == 1)
			 return (KeyReference)result[0];
		}

		return null;
	}

	private void open(KeyReference keyReference) {
		if (keyReference == null)
			return;

		try {
			IEditorPart part= EditorUtility.openInEditor(keyReference.storage, true);
			EditorUtility.revealInEditor(part, keyReference.offset, keyReference.length);
		} catch (JavaModelException e) {
			JavaPlugin.log(new Status(IStatus.ERROR, JavaPlugin.getPluginId(),
				IJavaStatusConstants.INTERNAL_ERROR, PropertiesFileEditorMessages.OpenAction_error_message, e));

			ErrorDialog.openError(fShell,
				getErrorDialogTitle(),
				PropertiesFileEditorMessages.OpenAction_error_messageProblems,
				e.getStatus());

		} catch (PartInitException x) {

			String message= null;

			IWorkbenchAdapter wbAdapter= (IWorkbenchAdapter)((IAdaptable)keyReference).getAdapter(IWorkbenchAdapter.class);
			if (wbAdapter != null)
				message= Messages.format(PropertiesFileEditorMessages.OpenAction_error_messageArgs,
						new String[] { wbAdapter.getLabel(keyReference), x.getLocalizedMessage() } );

			if (message == null)
				message= Messages.format(PropertiesFileEditorMessages.OpenAction_error_message, x.getLocalizedMessage());

			MessageDialog.openError(fShell,
				PropertiesFileEditorMessages.OpenAction_error_messageProblems,
				message);
		}
	}

	private String getErrorDialogTitle() {
		return PropertiesFileEditorMessages.OpenAction_error_title;
	}

	private void showError(CoreException e) {
		ExceptionHandler.handle(e, fShell, getErrorDialogTitle(), PropertiesFileEditorMessages.OpenAction_error_message);
	}

	private void showErrorInStatusLine(final String message) {
		fShell.getDisplay().beep();
		final IEditorStatusLine statusLine= (IEditorStatusLine)fEditor.getAdapter(IEditorStatusLine.class);
		if (statusLine != null) {
			fShell.getDisplay().asyncExec(new Runnable() {
				/*
				 * @see java.lang.Runnable#run()
				 */
				public void run() {
					statusLine.setMessage(true, message, null);
				}
			});
		}
	}

	/**
	 * Returns whether we search the key in double-quotes or not.
	 * <p>
	 * XXX: This is a hack to improve the accuracy of matches, see https://bugs.eclipse.org/bugs/show_bug.cgi?id=81140
	 * </p>
	 *
	 * @return <code>true</code> if we search for double-quoted key
	 */
	private boolean useDoubleQuotedKey() {
		if (fStorage == null)
			return false;

		String name= fStorage.getName();

		return name != null && !"about.properties".equals(name) && !"feature.properties".equals(name) && !"plugin.properties".equals(name);  //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
	}

	/**
	 * Searches references to the given key in the given scope.
	 *
	 * @param scope the scope
	 * @param key the properties key
	 * @return the references or <code>null</code> if the search has been canceled by the user
	 */
	private KeyReference[] search(final IResource scope, String key) {
		if (key == null)
			return new KeyReference[0];

		final List<KeyReference> result= new ArrayList<KeyReference>(5);
		final String searchString;

		// XXX: This is a hack to improve the accuracy of matches, see https://bugs.eclipse.org/bugs/show_bug.cgi?id=81140
		if (useDoubleQuotedKey()) {
			StringBuffer buf= new StringBuffer("\""); //$NON-NLS-1$
			buf.append(fPropertiesKey);
			buf.append('"');
			searchString= buf.toString();
		} else
			searchString= fPropertiesKey;

		try {
			fEditor.getEditorSite().getWorkbenchWindow().getWorkbench().getProgressService().busyCursorWhile(
				new IRunnableWithProgress() {
					public void run(IProgressMonitor monitor) throws InvocationTargetException, InterruptedException {
						ResultCollector collector= new ResultCollector(result, monitor, useDoubleQuotedKey());
						TextSearchEngine engine= new TextSearchEngine();
						engine.search(createScope(scope), false, collector, new MatchLocator(searchString, true, false));
					}
				}
			);
		} catch (InvocationTargetException ex) {
			String message= PropertiesFileEditorMessages.OpenAction_error_messageErrorSearchingKey;
			showError(new CoreException(new Status(IStatus.ERROR, JavaUI.ID_PLUGIN, IStatus.OK, message, ex.getTargetException())));
		} catch (InterruptedException ex) {
			return null; // canceled
		}

		return result.toArray(new KeyReference[result.size()]);
	}

	private static SearchScope createScope(IResource scope) {
		SearchScope result= SearchScope.newSearchScope("", new IResource[] { scope }); //$NON-NLS-1$

		// XXX: Should be configurable via preference, see https://bugs.eclipse.org/bugs/show_bug.cgi?id=81117
		result.addFileNamePattern("*.java"); //$NON-NLS-1$
		result.addFileNamePattern("*.xml"); //$NON-NLS-1$
		result.addFileNamePattern("*.ini"); //$NON-NLS-1$

		return result;
	}

	/*
	 * @see org.eclipse.jdt.internal.ui.javaeditor.IHyperlink#getTypeLabel()
	 */
	public String getTypeLabel() {
		return null;
	}

	/*
	 * @see org.eclipse.jdt.internal.ui.javaeditor.IHyperlink#getHyperlinkText()
	 */
	public String getHyperlinkText() {
		return null;
	}
}

/*******************************************************************************
 * Copyright (c) 2000, 2006 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package org.eclipse.jdt.internal.ui.wizards.buildpaths.newsourcepage;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.SubProgressMonitor;

import org.eclipse.jface.operation.IRunnableContext;
import org.eclipse.jface.operation.IRunnableWithProgress;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.StructuredSelection;

import org.eclipse.ui.IWorkbenchSite;
import org.eclipse.ui.PlatformUI;

import org.eclipse.jdt.core.IClasspathEntry;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IPackageFragmentRoot;
import org.eclipse.jdt.core.JavaModelException;

import org.eclipse.jdt.internal.corext.buildpath.ClasspathModifier;
import org.eclipse.jdt.internal.corext.buildpath.ClasspathModifier.IClasspathModifierListener;

import org.eclipse.jdt.internal.ui.JavaPlugin;
import org.eclipse.jdt.internal.ui.wizards.NewWizardMessages;
import org.eclipse.jdt.internal.ui.wizards.buildpaths.CPListElement;
import org.eclipse.jdt.internal.ui.wizards.buildpaths.CPListElementAttribute;

//Warning: This is unused and untested code. Images and descriptions are missing too.
//SelectedElements iff enabled: IJavaProject || IPackageFragmentRoot || CPListElementAttribute
public class ResetAction extends BuildpathModifierAction {

	private final IClasspathModifierListener fListener;
	private final IRunnableContext fContext;

	public ResetAction(IWorkbenchSite site) {
		this(site, PlatformUI.getWorkbench().getProgressService(), null);
	}

	public ResetAction(IWorkbenchSite site, IRunnableContext context, IClasspathModifierListener listener) {
		super(site);
		
		fContext= context;
		fListener= listener;

		setText(NewWizardMessages.NewSourceContainerWorkbookPage_ToolBar_Reset_tooltip);
		setToolTipText(NewWizardMessages.NewSourceContainerWorkbookPage_ToolBar_Reset_tooltip);
    }

	/**
	 * {@inheritDoc}
	 */
	public void run() {
		final IRunnableWithProgress runnable= new IRunnableWithProgress() {
			public void run(IProgressMonitor monitor) throws InvocationTargetException, InterruptedException {
				try {
					Object firstElement= getSelectedElements().get(0);
					IJavaProject project= null;
					if (firstElement instanceof IJavaProject) {
						project= (IJavaProject)firstElement;
					} else if (firstElement instanceof IPackageFragmentRoot) {
						project= ((IPackageFragmentRoot)firstElement).getJavaProject();
					} else {
						project= ((CPListElementAttribute)firstElement).getParent().getJavaProject();
					}
					
					List result= reset(getSelectedElements(), project, monitor);
					selectAndReveal(new StructuredSelection(result));					
				} catch (CoreException e) {
					throw new InvocationTargetException(e);
				}
			}
		};
		try {
	        fContext.run(false, false, runnable);
        } catch (InvocationTargetException e) {
        	if (e.getCause() instanceof CoreException) {
				showExceptionDialog((CoreException)e.getCause(), ""); //$NON-NLS-1$
			} else {
				JavaPlugin.log(e);
			}
        } catch (InterruptedException e) {
        }
	}
	
	private List reset(List selection, IJavaProject project, IProgressMonitor monitor) throws JavaModelException {
	    if (monitor == null)
        	monitor= new NullProgressMonitor();
        try {
        	monitor.beginTask(NewWizardMessages.ClasspathModifier_Monitor_Resetting, selection.size()); 
        	List entries= ClasspathModifier.getExistingEntries(project);
        	List result= new ArrayList();
        	for (int i= 0; i < selection.size(); i++) {
        		Object element= selection.get(i);
        		if (element instanceof IJavaElement) {
        			IJavaElement javaElement= (IJavaElement) element;
        			IPackageFragmentRoot root;
        			if (element instanceof IJavaProject)
        				root= project.getPackageFragmentRoot(project.getResource());
        			else
        				root= (IPackageFragmentRoot) element;
        			CPListElement entry= ClasspathModifier.getClasspathEntry(entries, root);
        			ClasspathModifier.resetFilters(javaElement, entry, project, new SubProgressMonitor(monitor, 1));
        			result.add(javaElement);
        		} else {
        			CPListElement selElement= ((CPListElementAttribute) element).getParent();
        			CPListElement entry= ClasspathModifier.getClasspathEntry(entries, selElement);
        			CPListElementAttribute outputFolder= ClasspathModifier.resetOutputFolder(entry, project);
        			result.add(outputFolder);
        		}
        	}
        
        	ClasspathModifier.commitClassPath(entries, project, fListener, null);
        	return result;
        } finally {
        	monitor.done();
        }
    }
	
	protected boolean canHandle(IStructuredSelection elements) {
		try {
	        for (Iterator iterator= elements.iterator(); iterator.hasNext();) {
	            Object element= iterator.next();
	            if (element instanceof IJavaProject) {
	            	IJavaProject project= (IJavaProject)element;
	            	if (!project.isOnClasspath(project))
	            		return false;
	            	
	            	IClasspathEntry entry= ClasspathModifier.getClasspathEntryFor(project.getPath(), project, IClasspathEntry.CPE_SOURCE);
	                if (entry.getInclusionPatterns().length == 0 && entry.getExclusionPatterns().length == 0)
	                    return false;
	            	
	        		return true;
	            } else if (element instanceof IPackageFragmentRoot) {
	            	if (ClasspathModifier.filtersSet((IPackageFragmentRoot)element))
	            		return true;
	            } else if (element instanceof CPListElementAttribute) {
	            	if (!ClasspathModifier.isDefaultOutputFolder((CPListElementAttribute)element))
	            		return true;
	            } else {
	            	return false;
	            }
	        }
        } catch (JavaModelException e) {
	        return false;
        }
		return false;
	}
}
/*
 * (c) Copyright IBM Corp. 2000, 2001.
 * All Rights Reserved.
 */
package org.eclipse.jdt.internal.ui.reorg;

import java.lang.reflect.InvocationTargetException;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.MultiStatus;
import org.eclipse.core.runtime.NullProgressMonitor;

import org.eclipse.jface.dialogs.ProgressMonitorDialog;
import org.eclipse.jface.viewers.ISelectionProvider;

import org.eclipse.jdt.core.JavaModelException;

import org.eclipse.jdt.internal.corext.refactoring.base.ChangeContext;
import org.eclipse.jdt.internal.corext.refactoring.base.Refactoring;
import org.eclipse.jdt.internal.ui.JavaPlugin;
import org.eclipse.jdt.internal.ui.actions.StructuredSelectionProvider;
import org.eclipse.jdt.internal.ui.refactoring.CreateChangeOperation;
import org.eclipse.jdt.internal.ui.refactoring.PerformChangeOperation;
import org.eclipse.jdt.internal.ui.refactoring.actions.RefactoringAction;
import org.eclipse.jdt.internal.ui.util.ExceptionHandler;

/**
 * Base class for actions related to reorganizing resources
 */
public abstract class ReorgAction extends RefactoringAction {
	
	public ReorgAction(String name, StructuredSelectionProvider p) {
		super(name, p);
	}
	
	public ReorgAction(String name, ISelectionProvider p) {
		super(name, p);
	}
	
	static boolean canActivate(Refactoring ref){
		try {
			return ref.checkActivation(new NullProgressMonitor()).isOK();
		} catch (JavaModelException e) {
			ExceptionHandler.handle(e, "Exception", "Unexpected exception. See log for details.");
			return false;
		}	
	}
	
	static MultiStatus perform(Refactoring ref) throws JavaModelException{	
		PerformChangeOperation op= new PerformChangeOperation(new CreateChangeOperation(ref, CreateChangeOperation.CHECK_NONE));
		ReorgExceptionHandler handler= new ReorgExceptionHandler();
		op.setChangeContext(new ChangeContext(handler));		
		try {
			//cannot fork - must run in the ui thread
			new ProgressMonitorDialog(JavaPlugin.getActiveWorkbenchShell()).run(false, true, op);
		} catch (InvocationTargetException e) {
			Throwable t= e.getTargetException();
			if (t instanceof CoreException)
				handler.getStatus().merge(((CoreException) t).getStatus());
			JavaPlugin.log(t);	
			if (t instanceof Error)
				throw (Error)t;
			if (t instanceof RuntimeException)
				throw (RuntimeException)t;
			//fall thru
		} catch (InterruptedException e) {
			//fall thru
		}
		return handler.getStatus();
	}	
}
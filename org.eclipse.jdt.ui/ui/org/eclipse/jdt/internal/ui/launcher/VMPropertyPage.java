/*
 * (c) Copyright IBM Corp. 2000, 2001.
 * All Rights Reserved.
 */

package org.eclipse.jdt.internal.ui.launcher;

import org.eclipse.core.runtime.CoreException;import org.eclipse.jdt.core.IJavaProject;import org.eclipse.jdt.launching.JavaRuntime;import org.eclipse.jface.viewers.ISelectionChangedListener;import org.eclipse.jface.viewers.SelectionChangedEvent;import org.eclipse.swt.SWT;import org.eclipse.swt.layout.FillLayout;import org.eclipse.swt.widgets.Composite;import org.eclipse.swt.widgets.Control;

/*
 * The page for setting java runtime
 */
public class VMPropertyPage extends JavaProjectPropertyPage {
	private VMSelector fVMSelector;
	
	public VMPropertyPage() {
		fVMSelector= new VMSelector();
		setDescription("Select the VM used to run Java programs");
	}
	
	protected Control createJavaContents(Composite ancestor) {
		noDefaultAndApplyButton();
		Composite parent= new Composite(ancestor, SWT.NULL);
		parent.setLayout(new FillLayout());
		
		Control vmSelector= fVMSelector.createContents(parent);
		fVMSelector.initFromProject(getJavaProject());
		fVMSelector.addSelectionChangedListener(new ISelectionChangedListener() {
			public void selectionChanged(SelectionChangedEvent event) {
				setValid(fVMSelector.validateSelection(event.getSelection()));
			}
		});
		return parent;
	}

	
	public boolean performOk() {
		IJavaProject project= getJavaProject();
		if (project != null) {
			try {
				JavaRuntime.setVM(project, fVMSelector.getSelectedVM());
			} catch (CoreException e) {
			}
		}
		return true;
	}
}
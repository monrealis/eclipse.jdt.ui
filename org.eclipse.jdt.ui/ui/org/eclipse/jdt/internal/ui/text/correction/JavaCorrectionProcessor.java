/*******************************************************************************
 * Copyright (c) 2000, 2002 International Business Machines Corp. and others.
 * All rights reserved. This program and the accompanying materials 
 * are made available under the terms of the Common Public License v0.5 
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/cpl-v05.html
 * 
 * Contributors:
 *     IBM Corporation - initial API and implementation
 ******************************************************************************/

package org.eclipse.jdt.internal.ui.text.correction;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;

import org.eclipse.core.resources.IMarker;
import org.eclipse.core.runtime.CoreException;

import org.eclipse.jface.text.ITextViewer;
import org.eclipse.jface.text.Position;
import org.eclipse.jface.text.contentassist.ICompletionProposal;
import org.eclipse.jface.text.contentassist.IContentAssistProcessor;
import org.eclipse.jface.text.contentassist.IContextInformation;
import org.eclipse.jface.text.contentassist.IContextInformationValidator;
import org.eclipse.jface.text.source.Annotation;
import org.eclipse.jface.text.source.IAnnotationModel;

import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IMarkerResolution;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.texteditor.MarkerAnnotation;

import org.eclipse.jdt.core.ICompilationUnit;

import org.eclipse.jdt.ui.JavaUI;

import org.eclipse.jdt.internal.ui.JavaPlugin;
import org.eclipse.jdt.internal.ui.javaeditor.IProblemAnnotation;
import org.eclipse.jdt.internal.ui.javaeditor.ProblemAnnotationIterator;
import org.eclipse.jdt.internal.ui.text.java.IJavaCompletionProposal;


public class JavaCorrectionProcessor implements IContentAssistProcessor {


	private static class CorrectionsComparator implements Comparator {
		
		private static Collator fgCollator= Collator.getInstance();
		
		public int compare(Object o1, Object o2) {
			if ((o1 instanceof IJavaCompletionProposal) && (o2 instanceof IJavaCompletionProposal)) {
				IJavaCompletionProposal e1= (IJavaCompletionProposal) o1;
				IJavaCompletionProposal e2= (IJavaCompletionProposal) o2;				
				int del= e2.getRelevance() - e1.getRelevance();
				if (del != 0) {
					return del;
				}
				return fgCollator.compare(e1.getDisplayString(), e1.getDisplayString());

			}				
			return fgCollator.compare(((ICompletionProposal) o1).getDisplayString(), ((ICompletionProposal) o1).getDisplayString());
		}
	}
	
	private static ICorrectionProcessor[] fCodeManipulationProcessors= null;
	
	public static ICorrectionProcessor[] getCodeManipulationProcessors() {
		if (fCodeManipulationProcessors == null) {
			fCodeManipulationProcessors= new ICorrectionProcessor[] {
				new QuickFixProcessor(),
				new QuickAssistProcessor()
			};
		}
		return fCodeManipulationProcessors;
	}
	
	public static boolean hasCorrections(int problemId) {
		return QuickFixProcessor.hasCorrections(problemId);
	}	
	
	private IEditorPart fEditor;

	
	/**
	 * Constructor for JavaCorrectionProcessor.
	 */
	public JavaCorrectionProcessor(IEditorPart editor) {
		fEditor= editor;
	}

	/*
	 * @see IContentAssistProcessor#computeCompletionProposals(ITextViewer, int)
	 */
	public ICompletionProposal[] computeCompletionProposals(ITextViewer viewer, int documentOffset) {
		
		ICompilationUnit cu= JavaUI.getWorkingCopyManager().getWorkingCopy(fEditor.getEditorInput());
		IAnnotationModel model= JavaUI.getDocumentProvider().getAnnotationModel(fEditor.getEditorInput());

		ArrayList proposals= new ArrayList();
		if (model != null) {
			int length= viewer != null ? viewer.getSelectedRange().y : 0;
			processProblemAnnotations(cu, model, documentOffset, length, proposals);
		}
		
		if (proposals.isEmpty()) {
			proposals.add(new NoCorrectionProposal(null, null));
		}
		ICompletionProposal[] res= (ICompletionProposal[]) proposals.toArray(new ICompletionProposal[proposals.size()]);
		Arrays.sort(res, new CorrectionsComparator());
		return res;
	}
	
	private boolean isAtPosition(int offset, Position pos) {
		return (pos != null) && (offset >= pos.getOffset() && offset <= (pos.getOffset() +  pos.getLength()));
	}
	

	private void processProblemAnnotations(ICompilationUnit cu, IAnnotationModel model, int offset, int length, ArrayList proposals) {
		CorrectionContext context= new CorrectionContext(cu);
		
		boolean noProbemFound= true;
		HashSet idsProcessed= new HashSet();
		Iterator iter= new ProblemAnnotationIterator(model, true);
		while (iter.hasNext()) {
			IProblemAnnotation annot= (IProblemAnnotation) iter.next();
			Position pos= model.getPosition((Annotation) annot);
			if (isAtPosition(offset, pos)) {
				int problemId= annot.getId();
				if (problemId != -1) {
					if (idsProcessed.add(new Integer(problemId))) { // collect only once per problem id
						context.initialize(pos.getOffset(), pos.getLength(), annot.getId(), annot.getArguments());
						collectCorrections(context, proposals);
						if (proposals.isEmpty()) {
							//proposals.add(new NoCorrectionProposal(pp, annot.getMessage()));
						}
					}					
				} else {
					if (annot instanceof MarkerAnnotation) {
						IMarker marker= ((MarkerAnnotation) annot).getMarker();
						IMarkerResolution[] res= PlatformUI.getWorkbench().getMarkerHelpRegistry().getResolutions(marker);
						for (int i= 0; i < res.length; i++) {
							proposals.add(new MarkerResolutionProposal(res[i], marker));
						}
					}
				}
				noProbemFound= false;
			}
		}
		if (noProbemFound) {
			context.initialize(offset, length, 0, null);
			collectCorrections(context, proposals);
		}
	}
	
	public static void collectCorrections(CorrectionContext context, ArrayList proposals) {
		ICorrectionProcessor[] processors= getCodeManipulationProcessors();
		for (int i= 0; i < processors.length; i++) {
			try {
				processors[i].process(context, proposals);
			} catch (CoreException e) {
				JavaPlugin.log(e);
			}
		}
	}
	
	/*
	 * @see IContentAssistProcessor#computeContextInformation(ITextViewer, int)
	 */
	public IContextInformation[] computeContextInformation(ITextViewer viewer, int documentOffset) {
		return null;
	}

	/*
	 * @see IContentAssistProcessor#getCompletionProposalAutoActivationCharacters()
	 */
	public char[] getCompletionProposalAutoActivationCharacters() {
		return null;
	}

	/*
	 * @see IContentAssistProcessor#getContextInformationAutoActivationCharacters()
	 */
	public char[] getContextInformationAutoActivationCharacters() {
		return null;
	}

	/*
	 * @see IContentAssistProcessor#getContextInformationValidator()
	 */
	public IContextInformationValidator getContextInformationValidator() {
		return null;
	}

	/*
	 * @see IContentAssistProcessor#getErrorMessage()
	 */
	public String getErrorMessage() {
		return null;
	}
}
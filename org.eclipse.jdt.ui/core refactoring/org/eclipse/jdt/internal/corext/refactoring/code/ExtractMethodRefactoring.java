/*******************************************************************************
 * Copyright (c) 2000, 2003 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials 
 * are made available under the terms of the Common Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/cpl-v10.html
 * 
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package org.eclipse.jdt.internal.corext.refactoring.code;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;

import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.core.dom.*;

import org.eclipse.jdt.internal.corext.Assert;
import org.eclipse.jdt.internal.corext.codemanipulation.CodeGenerationSettings;
import org.eclipse.jdt.internal.corext.codemanipulation.ImportEdit;
import org.eclipse.jdt.internal.corext.dom.ASTFlattener;
import org.eclipse.jdt.internal.corext.dom.ASTNodeFactory;
import org.eclipse.jdt.internal.corext.dom.ASTNodes;
import org.eclipse.jdt.internal.corext.dom.ASTRewrite;
import org.eclipse.jdt.internal.corext.dom.LinkedNodeFinder;
import org.eclipse.jdt.internal.corext.dom.Selection;
import org.eclipse.jdt.internal.corext.refactoring.Checks;
import org.eclipse.jdt.internal.corext.refactoring.ParameterInfo;
import org.eclipse.jdt.internal.corext.refactoring.RefactoringCoreMessages;
import org.eclipse.jdt.internal.corext.refactoring.base.IChange;
import org.eclipse.jdt.internal.corext.refactoring.base.Refactoring;
import org.eclipse.jdt.internal.corext.refactoring.base.RefactoringStatus;
import org.eclipse.jdt.internal.corext.refactoring.changes.CompilationUnitChange;
import org.eclipse.jdt.internal.corext.refactoring.util.ResourceUtil;
import org.eclipse.jdt.internal.corext.textmanipulation.GroupDescription;
import org.eclipse.jdt.internal.corext.textmanipulation.MultiTextEdit;
import org.eclipse.jdt.internal.corext.textmanipulation.TextBuffer;
import org.eclipse.jdt.internal.corext.textmanipulation.TextEdit;
import org.eclipse.jdt.internal.corext.util.WorkingCopyUtil;

/**
 * Extracts a method in a compilation unit based on a text selection range.
 * <p>
 * <bf>NOTE:<bf> This class/interface is part of an interim API that is still under development 
 * and expected to change significantly before reaching stability. It is being made available at 
 * this early stage to solicit feedback from pioneering adopters on the understanding that any 
 * code that uses this API will almost certainly be broken (repeatedly) as the API evolves.</p>
 */
public class ExtractMethodRefactoring extends Refactoring {

	private ICompilationUnit fCUnit;
	private ImportEdit fImportEdit;
	private int fSelectionStart;
	private int fSelectionLength;
	private AST fAST;
	private ASTRewrite fRewriter;
	private ExtractMethodAnalyzer fAnalyzer;
	private int fVisibility;
	private String fMethodName;
	private boolean fThrowRuntimeExceptions;
	private List fParameterInfos;
	private Set fUsedNames;
	private boolean fReplaceDuplicates;
	private SnippetFinder.Match[] fDuplicates;

	private static final String EMPTY= ""; //$NON-NLS-1$
	

	private static class UsedNamesCollector extends ASTVisitor {
		private Set result= new HashSet();
		private Set fIgnore= new HashSet();
		public static Set perform(ASTNode[] nodes) {
			UsedNamesCollector collector= new UsedNamesCollector();
			for (int i= 0; i < nodes.length; i++) {
				nodes[i].accept(collector);
			}
			return collector.result;
		}
		public boolean visit(FieldAccess node) {
			Expression exp= node.getExpression();
			if (exp != null)
				fIgnore.add(node.getName());
			return true;
		}
		public void endVisit(FieldAccess node) {
			fIgnore.remove(node.getName());
		}
		public boolean visit(MethodInvocation node) {
			Expression exp= node.getExpression();
			if (exp != null)
				fIgnore.add(node.getName());
			return true;
		}
		public void endVisit(MethodInvocation node) {
			fIgnore.remove(node.getName());
		}
		public boolean visit(QualifiedName node) {
			fIgnore.add(node.getName());
			return true;
		}
		public void endVisit(QualifiedName node) {
			fIgnore.remove(node.getName());
		}
		public boolean visit(SimpleName node) {
			if (!fIgnore.contains(node))
				result.add(node.getIdentifier());
			return true;
		}
		public boolean visit(TypeDeclaration node) {
			result.add(node.getName().getIdentifier());
			// don't dive into type declaration since they open a new
			// context.
			return false;
		}
	}
	
	/**
	 * Creates a new extract method refactoring.
	 *
	 * @param cu the compilation unit which is going to be modified.
	 * @param accessor a callback object to access the source this refactoring is working on.
	 */
	private ExtractMethodRefactoring(ICompilationUnit cu, int selectionStart, int selectionLength, CodeGenerationSettings settings) throws JavaModelException {
		Assert.isNotNull(cu);
		Assert.isNotNull(settings);
		fCUnit= cu;
		fImportEdit= new ImportEdit(cu, settings);
		fMethodName= "extracted"; //$NON-NLS-1$
		fSelectionStart= selectionStart;
		fSelectionLength= selectionLength;
		fVisibility= -1;
	}
	
	public static ExtractMethodRefactoring create(ICompilationUnit cu, int selectionStart, int selectionLength, CodeGenerationSettings settings) throws JavaModelException {
		return new ExtractMethodRefactoring(cu, selectionStart, selectionLength, settings);
	}
	
	/* (non-Javadoc)
	 * Method declared in IRefactoring
	 */
	 public String getName() {
	 	return RefactoringCoreMessages.getFormattedString("ExtractMethodRefactoring.name", new String[]{fMethodName, fCUnit.getElementName()}); //$NON-NLS-1$
	 }

	/**
	 * Checks if the refactoring can be activated. Activation typically means, if a
	 * corresponding menu entry can be added to the UI.
	 *
	 * @param pm a progress monitor to report progress during activation checking.
	 * @return the refactoring status describing the result of the activation check.	 
	 */
	public RefactoringStatus checkActivation(IProgressMonitor pm) throws JavaModelException {
		try{
			pm.beginTask(RefactoringCoreMessages.getString("ExtractMethodRefactoring.checking_selection"), 1); //$NON-NLS-1$
			RefactoringStatus result= new RefactoringStatus();
			
			if (fSelectionStart < 0 || fSelectionLength == 0)
				return mergeTextSelectionStatus(result);
			
			result.merge(Checks.validateModifiesFiles(ResourceUtil.getFiles(new ICompilationUnit[]{fCUnit})));
			if (result.hasFatalError())
				return result;
			
			CompilationUnit root= AST.parseCompilationUnit(fCUnit, true);
			fAST= root.getAST();
			root.accept(createVisitor());
			
			result.merge(fAnalyzer.checkActivation());
			if (result.hasFatalError())
				return result;
			if (fVisibility == -1) {
				setVisibility(Modifier.PRIVATE);
			}
			initializeParameterInfos();
			initializeUsedNames();
			initializeDuplicates();
			return result;
		} finally {
			pm.worked(1);
			pm.done();
		}
	}
	
	private ASTVisitor createVisitor() throws JavaModelException {
		fAnalyzer= new ExtractMethodAnalyzer(fCUnit, Selection.createFromStartLength(fSelectionStart, fSelectionLength));
		return fAnalyzer;
	}
	
	/**
	 * Sets the method name to be used for the extracted method.
	 *
	 * @param name the new method name.
	 */	
	public void setMethodName(String name) {
		fMethodName= name;
	}
	
	/**
	 * Returns the method name to be used for the extracted method.
	 * @return the method name to be used for the extracted method.
	 */
	public String getMethodName() {
		return fMethodName;
	} 
	
	/**
	 * Sets the visibility of the new method.
	 * 
	 * @param visibility the visibility of the new method. Valid values are
	 *  "public", "protected", "", and "private"
	 */
	public void setVisibility(int visibility) {
		fVisibility= visibility;
	}
	
	/**
	 * Returns the visibility of the new method.
	 * 
	 * @return the visibility of the new method
	 */
	public int getVisibility() {
		return fVisibility;
	}
	
	/**
	 * Returns the parameter infos.
	 * @return a list of parameter infos.
	 */
	public List getParameterInfos() {
		return fParameterInfos;
	}
	
	/**
	 * Sets whether the new method signature throws runtime exceptions.
	 * 
	 * @param throwsRuntimeExceptions flag indicating if the new method
	 * 	throws runtime exceptions
	 */
	public void setThrowRuntimeExceptions(boolean throwRuntimeExceptions) {
		fThrowRuntimeExceptions= throwRuntimeExceptions;
	}
	
	/**
	 * Checks if the new method name is a valid method name. This method doesn't
	 * check if a method with the same name already exists in the hierarchy. This
	 * check is done in <code>checkInput</code> since it is expensive.
	 */
	public RefactoringStatus checkMethodName() {
		return Checks.checkMethodName(fMethodName);
	}
	
	/**
	 * Checks if the parameter names are valid.
	 */
	public RefactoringStatus checkParameterNames() {
		RefactoringStatus result= new RefactoringStatus();
		for (Iterator iter= fParameterInfos.iterator(); iter.hasNext();) {
			ParameterInfo parameter= (ParameterInfo)iter.next();
			result.merge(Checks.checkIdentifier(parameter.getNewName()));
			for (Iterator others= fParameterInfos.iterator(); others.hasNext();) {
				ParameterInfo other= (ParameterInfo) others.next();
				if (parameter != other && other.getNewName().equals(parameter.getNewName())) {
					result.addError(RefactoringCoreMessages.getFormattedString(
						"ExtractMethodRefactoring.error.sameParameter", //$NON-NLS-1$
						other.getNewName()));
					return result;
				}
			}
			if (parameter.isRenamed() && fUsedNames.contains(parameter.getNewName())) {
				result.addError(RefactoringCoreMessages.getFormattedString(
					"ExtractMethodRefactoring.error.nameInUse", //$NON-NLS-1$
					parameter.getNewName()));
				return result;
			}
		}
		return result;
	}
	
	/**
	 * Returns the names already in use in the selected statements/expressions.
	 * 
	 * @return names already in use.
	 */
	public Set getUsedNames() {
		return fUsedNames;
	}
	
	/* (non-Javadoc)
	 * Method declared in Refactoring
	 */
	public RefactoringStatus checkInput(IProgressMonitor pm) throws JavaModelException {
		pm.beginTask(RefactoringCoreMessages.getString("ExtractMethodRefactoring.checking_new_name"), 2); //$NON-NLS-1$
		pm.subTask(EMPTY);
		
		RefactoringStatus result= checkMethodName();
		result.merge(checkParameterNames());
		pm.worked(1);
		
		MethodDeclaration node= fAnalyzer.getEnclosingMethod();
		if (node != null) {
			fAnalyzer.checkInput(result, fMethodName, fCUnit.getJavaProject(), fAST);
			pm.worked(1);
		
		}
		pm.done();
		return result;
	}
	
	/* (non-Javadoc)
	 * Method declared in IRefactoring
	 */
	public IChange createChange(IProgressMonitor pm) throws JavaModelException {
		if (fMethodName == null)
			return null;
		
		fAnalyzer.aboutToCreateChange();
		MethodDeclaration method= fAnalyzer.getEnclosingMethod();
		TypeDeclaration type= (TypeDeclaration)ASTNodes.getParent(method, TypeDeclaration.class);
		fRewriter= new ASTRewrite(type);
		String sourceMethodName= method.getName().getIdentifier();
		
		CompilationUnitChange result= null;
		try {
			result= new CompilationUnitChange(
				RefactoringCoreMessages.getFormattedString("ExtractMethodRefactoring.change_name", new String[]{fMethodName, sourceMethodName}),  //$NON-NLS-1$
				fCUnit);
		
			MultiTextEdit root= new MultiTextEdit();
			result.setEdit(root);
			TextBuffer buffer= null;
			
			try {
				// This is cheap since the compilation unit is already open in a editor.
				buffer= TextBuffer.create((IFile)WorkingCopyUtil.getOriginal(fCUnit).getResource());
				
				ASTNode[] selectedNodes= fAnalyzer.getSelectedNodes();
				ASTNodes.expandRange(selectedNodes, buffer, fSelectionStart, fSelectionLength);
				ASTNode target= getTargetNode(selectedNodes);
				
				MethodDeclaration mm= createNewMethod(fMethodName, true, target);
				fRewriter.markAsInserted(mm, RefactoringCoreMessages.getFormattedString("ExtractMethodRefactoring.add_method", fMethodName)); //$NON-NLS-1$
				List container= ASTNodes.getContainingList(method);
				container.add(container.indexOf(method) + 1, mm);
				
				String description= RefactoringCoreMessages.getFormattedString("ExtractMethodRefactoring.substitute_with_call", fMethodName); //$NON-NLS-1$
				ASTNode[] callNodes= createCallNodes(null);
				fRewriter.markAsReplaced(target, callNodes[0], description);
				if (callNodes.length > 1) {
					container= ASTNodes.getContainingList(target);
					int index= container.indexOf(target);
					for (int i= 1; i < callNodes.length; i++) {
						ASTNode node= callNodes[i];
						container.add(index + i, node);
						fRewriter.markAsInserted(node, description);
					}
				}
				
				replaceDuplicates();
			
				if (!fImportEdit.isEmpty()) {
					root.add(fImportEdit);
					result.addGroupDescription(new GroupDescription(
						RefactoringCoreMessages.getString("ExtractMethodRefactoring.organize_imports"), //$NON-NLS-1$
						new TextEdit[] {fImportEdit}
					));
				}
				
				List groups= new ArrayList(2);
				MultiTextEdit changes= new MultiTextEdit();
				fRewriter.rewriteNode(buffer, changes, groups);
				root.add(changes);
				result.addGroupDescriptions((GroupDescription[])groups.toArray(new GroupDescription[groups.size()]));
				
				fRewriter.removeModifications();
			} finally {
				TextBuffer.release(buffer);
			}
		} catch (JavaModelException e){
			throw e;			
		} catch (CoreException e) {
			throw new JavaModelException(e);
		}
		return result;
	}
	
	/**
	 * Returns the signature of the new method.
	 * 
	 * @return the signature of the extracted method
	 */
	public String getSignature() {
		return getSignature(fMethodName);
	}
	
	/**
	 * Returns the signature of the new method.
	 * 
	 * @param methodName the method name used for the new method
	 * @return the signature of the extracted method
	 */
	public String getSignature(String methodName) {
		MethodDeclaration method= createNewMethod(methodName, false, null);
		method.setBody(fAST.newBlock());
		ASTFlattener flattener= new ASTFlattener() {
			public boolean visit(Block node) {
				return false;
			}
		};
		method.accept(flattener);
		return flattener.getResult();		
	}
	
	/**
	 * Returns the number of duplicate code snippets found.
	 * 
	 * @return the number of duplicate code fragments
	 */
	public int getNumberOfDuplicates() {
		if (fDuplicates == null)
			return 0;
		int result=0;
		for (int i= 0; i < fDuplicates.length; i++) {
			if (!fDuplicates[i].isMethodBody())
				result++;
		}
		return result;
	}
	
	public void setReplaceDuplicates(boolean replace) {
		fReplaceDuplicates= replace;
	}
	
	//---- Helper methods ------------------------------------------------------------------------
	
	private void initializeParameterInfos() {
		IVariableBinding[] arguments= fAnalyzer.getArguments();
		fParameterInfos= new ArrayList(arguments.length);
		ASTNode root= fAnalyzer.getEnclosingMethod();
		for (int i= 0; i < arguments.length; i++) {
			IVariableBinding argument= arguments[i];
			if (argument == null)
				continue;
			VariableDeclaration declaration= ASTNodes.findVariableDeclaration(argument, root);
			ParameterInfo info= new ParameterInfo(argument, getType(declaration), argument.getName(), i);
			info.setData(argument);
			fParameterInfos.add(info);
		}
	}
	
	private void initializeUsedNames() {
		fUsedNames= UsedNamesCollector.perform(fAnalyzer.getSelectedNodes());
		for (Iterator iter= fParameterInfos.iterator(); iter.hasNext();) {
			ParameterInfo parameter= (ParameterInfo)iter.next();
			fUsedNames.remove(parameter.getOldName());
		}
	}
	
	private void initializeDuplicates() {
		fDuplicates= SnippetFinder.perform(
			ASTNodes.getParent(fAnalyzer.getEnclosingMethod(), TypeDeclaration.class), 
			fAnalyzer.getSelectedNodes());
		fReplaceDuplicates= fDuplicates.length > 0;
	}
	
	private RefactoringStatus mergeTextSelectionStatus(RefactoringStatus status) {
		status.addFatalError(RefactoringCoreMessages.getString("ExtractMethodRefactoring.no_set_of_statements")); //$NON-NLS-1$
		return status;	
	}
	
	private String getType(VariableDeclaration declaration) {
		return ASTNodes.asString(ASTNodes.getType(declaration));
	}
	
	//---- Code generation -----------------------------------------------------------------------
	
	private ASTNode getTargetNode(ASTNode[] nodes) {
		ASTNode result;
		if (nodes.length == 1) {
			return nodes[0];
		} else {
			ASTNode first= nodes[0];
			List container= ASTNodes.getContainingList(first);
			result= fRewriter.collapseNodes(container, container.indexOf(first), nodes.length);
		}
		return result;
	}
	
	private ASTNode[] createCallNodes(SnippetFinder.Match duplicate) {
		List result= new ArrayList(2);
		
		IVariableBinding[] locals= fAnalyzer.getCallerLocals();
		for (int i= 0; i < locals.length; i++) {
			result.add(createDeclaration(locals[i], null));
		}
		
		MethodInvocation invocation= fAST.newMethodInvocation();
		invocation.setName(fAST.newSimpleName(fMethodName));
		List arguments= invocation.arguments();
		for (int i= 0; i < fParameterInfos.size(); i++) {
			ParameterInfo parameter= ((ParameterInfo)fParameterInfos.get(i));
			if (duplicate == null) {
				arguments.add(ASTNodeFactory.newName(fAST, parameter.getOldName()));
			} else {
				arguments.add(ASTNodeFactory.newName(fAST, duplicate.getMappedName(parameter.getOldBinding()).getIdentifier()));
			}
		}		
		
		ASTNode call;		
		int returnKind= fAnalyzer.getReturnKind();
		switch (returnKind) {
			case ExtractMethodAnalyzer.ACCESS_TO_LOCAL:
				IVariableBinding binding= fAnalyzer.getReturnLocal();
				if (binding != null) {
					VariableDeclarationStatement decl= createDeclaration(binding, invocation);
					call= decl;
				} else {
					Assignment assignment= fAST.newAssignment();
					assignment.setLeftHandSide(ASTNodeFactory.newName(fAST, fAnalyzer.getReturnValue().getName()));
					assignment.setRightHandSide(invocation);
					call= assignment;
				}
				break;
			case ExtractMethodAnalyzer.RETURN_STATEMENT_VALUE:
				ReturnStatement rs= fAST.newReturnStatement();
				rs.setExpression(invocation);
				call= rs;
				break;
			default:
				call= invocation;
		}
		
		if (call instanceof Expression && !fAnalyzer.isExpressionSelected()) {
			call= fAST.newExpressionStatement((Expression)call);
		}
		result.add(call);
		
		// We have a void return statement. The code looks like
		// extracted();
		// return;	
		if (returnKind == ExtractMethodAnalyzer.RETURN_STATEMENT_VOID && !fAnalyzer.isLastStatementSelected()) {
			result.add(fAST.newReturnStatement());
		}
		return (ASTNode[])result.toArray(new ASTNode[result.size()]);		
	}
	
	private void replaceDuplicates() {
		int numberOf= getNumberOfDuplicates();
		if (numberOf == 0 || !fReplaceDuplicates)
			return;
		String description= null;
		if (numberOf == 1)
			description= RefactoringCoreMessages.getFormattedString("ExtractMethodRefactoring.duplicates.single", fMethodName); //$NON-NLS-1$
		else
			description= RefactoringCoreMessages.getFormattedString("ExtractMethodRefactoring.duplicates.multi", fMethodName); //$NON-NLS-1$
			
		for (int d= 0; d < fDuplicates.length; d++) {
			SnippetFinder.Match duplicate= fDuplicates[d];
			if (!duplicate.isMethodBody()) {
				ASTNode target= getTargetNode(duplicate.getNodes());
				ASTNode[] callNodes= createCallNodes(duplicate);
				fRewriter.markAsReplaced(target, callNodes[0], description);
				if (callNodes.length > 1) {
					List container= ASTNodes.getContainingList(target);
					int index= container.indexOf(target);
					for (int n= 1; n < callNodes.length; n++) {
						ASTNode node= callNodes[n];
						container.add(index + n, node);
						fRewriter.markAsInserted(node, description);
					}
				}
			}
		}		
	}
	
	private MethodDeclaration createNewMethod(String name, boolean code, ASTNode selection) {
		MethodDeclaration result= fAST.newMethodDeclaration();
		int modifiers= fVisibility;
		if (Modifier.isStatic(fAnalyzer.getEnclosingMethod().getModifiers()) || fAnalyzer.getForceStatic()) {
			modifiers= modifiers | Modifier.STATIC;
		}
		result.setModifiers(modifiers);
		if (fAnalyzer.isExpressionSelected()) {
			String type= fImportEdit.addImport(ASTNodes.asString(fAnalyzer.getReturnType()));
			result.setReturnType(ASTNodeFactory.newType(fAST, type));
		} else {
			result.setReturnType((Type)ASTNode.copySubtree(fAST, fAnalyzer.getReturnType()));
		}
		result.setName(fAST.newSimpleName(name));
		
		List parameters= result.parameters();
		for (int i= 0; i < fParameterInfos.size(); i++) {
			ParameterInfo info= (ParameterInfo)fParameterInfos.get(i);
			VariableDeclaration infoDecl= getVariableDeclaration(info);
			SingleVariableDeclaration parameter= fAST.newSingleVariableDeclaration();
			parameter.setModifiers(ASTNodes.getModifiers(infoDecl));
			parameter.setType((Type)ASTNode.copySubtree(fAST, ASTNodes.getType(infoDecl)));
			parameter.setName(fAST.newSimpleName(info.getNewName()));
			parameters.add(parameter);
		}
		
		List exceptions= result.thrownExceptions();
		ITypeBinding[] exceptionTypes= fAnalyzer.getExceptions(fThrowRuntimeExceptions, fAST);
		for (int i= 0; i < exceptionTypes.length; i++) {
			ITypeBinding exceptionType= exceptionTypes[i];
			exceptions.add(ASTNodeFactory.newName(fAST, fImportEdit.addImport(exceptionType)));
		}
		if (code)
			result.setBody(createMethodBody(selection));
		
		return result;
	}
	
	private Block createMethodBody(ASTNode selection) {
		Block result= fAST.newBlock();
		List statements= result.statements();
		
		// Locals that are not passed as an arguments since the extracted method only
		// writes to them
		IVariableBinding[] methodLocals= fAnalyzer.getMethodLocals();
		for (int i= 0; i < methodLocals.length; i++) {
			if (methodLocals[i] != null) {
				result.statements().add(createDeclaration(methodLocals[i], null));
			}
		}

		// Rewrite local names		
		for (Iterator iter= fParameterInfos.iterator(); iter.hasNext();) {
			ParameterInfo parameter= (ParameterInfo)iter.next();
			if (parameter.isRenamed()) {
				SimpleName[] oldNames= LinkedNodeFinder.perform(selection, (IBinding) parameter.getData());
				for (int i= 0; i < oldNames.length; i++) {
					fRewriter.markAsReplaced(oldNames[i], fAST.newSimpleName(parameter.getNewName()));
				}
			}
		}
		
		boolean extractsExpression= fAnalyzer.isExpressionSelected();
		if (extractsExpression) {
			ITypeBinding binding= fAnalyzer.getExpressionBinding();
			if (binding != null && (!binding.isPrimitive() || !"void".equals(binding.getName()))) { //$NON-NLS-1$
				ReturnStatement rs= fAST.newReturnStatement();
				rs.setExpression((Expression)fRewriter.createCopy(selection));
				fRewriter.markAsInserted(rs);
				statements.add(rs);
			} else {
				ExpressionStatement st= fAST.newExpressionStatement((Expression)fRewriter.createCopy(selection));
				fRewriter.markAsInserted(st);
				statements.add(st);
			}
		} else {
			statements.add(fRewriter.createCopy(selection));
			IVariableBinding returnValue= fAnalyzer.getReturnValue();
			if (returnValue != null) {
				ReturnStatement rs= fAST.newReturnStatement();
				rs.setExpression(fAST.newSimpleName(returnValue.getName()));
				statements.add(rs);				
			}
		}
		return result;
	}
	
	private VariableDeclaration getVariableDeclaration(ParameterInfo parameter) {
		return ASTNodes.findVariableDeclaration((IVariableBinding)parameter.getData(), fAnalyzer.getEnclosingMethod());
	}
	
	private VariableDeclarationStatement createDeclaration(IVariableBinding binding, Expression intilizer) {
		VariableDeclaration original= ASTNodes.findVariableDeclaration(binding, fAnalyzer.getEnclosingMethod());
		VariableDeclarationFragment fragment= fAST.newVariableDeclarationFragment();
		fragment.setName((SimpleName)ASTNode.copySubtree(fAST, original.getName()));
		fragment.setInitializer(intilizer);	
		VariableDeclarationStatement result= fAST.newVariableDeclarationStatement(fragment);
		result.setModifiers(ASTNodes.getModifiers(original));
		result.setType((Type)ASTNode.copySubtree(fAST, ASTNodes.getType(original)));
		return result;
	}
}

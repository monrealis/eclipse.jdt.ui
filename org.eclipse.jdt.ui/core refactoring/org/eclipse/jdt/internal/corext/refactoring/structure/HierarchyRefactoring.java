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
package org.eclipse.jdt.internal.corext.refactoring.structure;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.eclipse.text.edits.MalformedTreeException;
import org.eclipse.text.edits.TextEdit;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.SubProgressMonitor;

import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.Document;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.TextUtilities;

import org.eclipse.ltk.core.refactoring.Refactoring;
import org.eclipse.ltk.core.refactoring.RefactoringStatus;
import org.eclipse.ltk.core.refactoring.RefactoringStatusContext;

import org.eclipse.jdt.core.Flags;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IField;
import org.eclipse.jdt.core.IInitializer;
import org.eclipse.jdt.core.IMember;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.ISourceRange;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.ITypeHierarchy;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.AnnotationTypeDeclaration;
import org.eclipse.jdt.core.dom.BodyDeclaration;
import org.eclipse.jdt.core.dom.ClassInstanceCreation;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.EnumDeclaration;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.FieldDeclaration;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.Javadoc;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.Modifier;
import org.eclipse.jdt.core.dom.Name;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.SingleVariableDeclaration;
import org.eclipse.jdt.core.dom.Type;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.eclipse.jdt.core.dom.TypeParameter;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;
import org.eclipse.jdt.core.dom.rewrite.ASTRewrite;
import org.eclipse.jdt.core.dom.rewrite.ITrackedNodePosition;
import org.eclipse.jdt.core.search.IJavaSearchConstants;
import org.eclipse.jdt.core.search.SearchMatch;
import org.eclipse.jdt.core.search.SearchPattern;

import org.eclipse.jdt.internal.corext.Assert;
import org.eclipse.jdt.internal.corext.codemanipulation.StubUtility;
import org.eclipse.jdt.internal.corext.dom.ASTNodeFactory;
import org.eclipse.jdt.internal.corext.dom.ModifierRewrite;
import org.eclipse.jdt.internal.corext.refactoring.RefactoringCoreMessages;
import org.eclipse.jdt.internal.corext.refactoring.RefactoringScopeFactory;
import org.eclipse.jdt.internal.corext.refactoring.RefactoringSearchEngine2;
import org.eclipse.jdt.internal.corext.refactoring.SearchResultGroup;
import org.eclipse.jdt.internal.corext.refactoring.base.JavaStatusContext;
import org.eclipse.jdt.internal.corext.refactoring.reorg.SourceReferenceUtil;
import org.eclipse.jdt.internal.corext.refactoring.util.JavaElementUtil;
import org.eclipse.jdt.internal.corext.refactoring.util.RefactoringASTParser;
import org.eclipse.jdt.internal.corext.refactoring.util.TextChangeManager;
import org.eclipse.jdt.internal.corext.util.JavaModelUtil;
import org.eclipse.jdt.internal.corext.util.JdtFlags;
import org.eclipse.jdt.internal.corext.util.Messages;
import org.eclipse.jdt.internal.corext.util.SearchUtils;
import org.eclipse.jdt.internal.corext.util.Strings;
import org.eclipse.jdt.internal.corext.util.WorkingCopyUtil;

import org.eclipse.jdt.internal.ui.JavaPlugin;

/**
 * Partial implementation of a refactoring executed on type hierarchies.
 */
public abstract class HierarchyRefactoring extends Refactoring {

	/**
	 * AST node visitor which performs the actual mapping.
	 */
	public static class TypeVariableMapper extends ASTVisitor {

		/** The type variable mapping to use */
		protected final TypeVariableMaplet[] fMapping;

		/** The AST rewrite to use */
		protected final ASTRewrite fRewrite;

		/**
		 * Creates a new type variable mapper.
		 * 
		 * @param rewrite The AST rewrite to use
		 * @param mapping The type variable mapping to use
		 */
		public TypeVariableMapper(final ASTRewrite rewrite, final TypeVariableMaplet[] mapping) {
			Assert.isNotNull(rewrite);
			Assert.isNotNull(mapping);
			fRewrite= rewrite;
			fMapping= mapping;
		}

		public final boolean visit(final SimpleName node) {
			final ITypeBinding binding= node.resolveTypeBinding();
			if (binding != null && binding.isTypeVariable()) {
				String name= null;
				for (int index= 0; index < fMapping.length; index++) {
					name= binding.getName();
					if (fMapping[index].getSourceName().equals(name) && node.getIdentifier().equals(name))
						fRewrite.set(node, SimpleName.IDENTIFIER_PROPERTY, fMapping[index].getTargetName(), null);
				}
			}
			return true;
		}
	}

	protected static boolean areAllFragmentsDeleted(final FieldDeclaration declaration, final List<ASTNode> declarationNodes) {
		for (final Iterator<ASTNode> iterator= declaration.fragments().iterator(); iterator.hasNext();) {
			if (!declarationNodes.contains(iterator.next()))
				return false;
		}
		return true;
	}

	protected static RefactoringStatus checkCallsToClassConstructors(final IType type, final IProgressMonitor monitor) throws JavaModelException {
		final RefactoringStatus result= new RefactoringStatus();
		final SearchResultGroup[] groups= ConstructorReferenceFinder.getConstructorReferences(type, monitor, result);
		final String message= Messages.format(RefactoringCoreMessages.HierarchyRefactoring_gets_instantiated, new Object[] { createTypeLabel(type)}); 

		ICompilationUnit unit= null;
		for (int index= 0; index < groups.length; index++) {
			unit= groups[index].getCompilationUnit();
			if (unit != null) {
				final CompilationUnit cuNode= new RefactoringASTParser(AST.JLS3).parse(unit, false);
				final ASTNode[] references= ASTNodeSearchUtil.getAstNodes(groups[index].getSearchResults(), cuNode);
				ASTNode node= null;
				for (int offset= 0; offset < references.length; offset++) {
					node= references[offset];
					if ((node instanceof ClassInstanceCreation) || ConstructorReferenceFinder.isImplicitConstructorReferenceNodeInClassCreations(node)) {
						RefactoringStatusContext context= JavaStatusContext.create(unit, node);
						result.addError(message, context);
					}
				}
			}
		}
		monitor.done();
		return result;
	}

	protected static void copyJavadocNode(final ASTRewrite rewrite, final IMember member, final BodyDeclaration oldDeclaration, final BodyDeclaration newDeclaration) throws JavaModelException {
		final Javadoc predecessor= oldDeclaration.getJavadoc();
		if (predecessor != null) {
			final IDocument buffer= new Document(member.getCompilationUnit().getBuffer().getContents());
			try {
				final String[] lines= Strings.convertIntoLines(buffer.get(predecessor.getStartPosition(), predecessor.getLength()));
				Strings.trimIndentation(lines, member.getJavaProject(), false);
				final Javadoc successor= (Javadoc) rewrite.createStringPlaceholder(Strings.concatenate(lines, TextUtilities.getDefaultLineDelimiter(buffer)), ASTNode.JAVADOC);
				newDeclaration.setJavadoc(successor);
			} catch (BadLocationException exception) {
				JavaPlugin.log(exception);
			}
		}
	}

	protected static void copyThrownExceptions(MethodDeclaration oldMethod, MethodDeclaration newMethod) {
		final AST ast= newMethod.getAST();
		for (int index= 0, n= oldMethod.thrownExceptions().size(); index < n; index++)
			newMethod.thrownExceptions().add(index, ASTNode.copySubtree(ast, (Name) oldMethod.thrownExceptions().get(index)));
	}

	protected static void copyTypeParameters(MethodDeclaration oldMethod, MethodDeclaration newMethod) {
		final AST ast= newMethod.getAST();
		for (int index= 0, n= oldMethod.typeParameters().size(); index < n; index++)
			newMethod.typeParameters().add(index, ASTNode.copySubtree(ast, (TypeParameter) oldMethod.typeParameters().get(index)));
	}

	protected static String createFieldLabel(final IField field) {
		return field.getElementName();
	}

	protected static String createLabel(final IMember member) {
		if (member instanceof IType)
			return createTypeLabel((IType) member);
		else if (member instanceof IMethod)
			return createMethodLabel((IMethod) member);
		else if (member instanceof IField)
			return createFieldLabel((IField) member);
		else if (member instanceof IInitializer)
			return RefactoringCoreMessages.HierarchyRefactoring_initializer; 
		Assert.isTrue(false);
		return null;
	}

	protected static String createMethodLabel(final IMethod method) {
		return JavaElementUtil.createMethodSignature(method);
	}

	protected static FieldDeclaration createNewFieldDeclarationNode(final ASTRewrite rewrite, final CompilationUnit unit, final IField field, final VariableDeclarationFragment oldFieldFragment, final TypeVariableMaplet[] mapping, final IProgressMonitor monitor, final RefactoringStatus status, final int modifiers) throws JavaModelException {
		final VariableDeclarationFragment newFragment= rewrite.getAST().newVariableDeclarationFragment();
		newFragment.setExtraDimensions(oldFieldFragment.getExtraDimensions());
		if (oldFieldFragment.getInitializer() != null) {
			Expression newInitializer= null;
			if (mapping.length > 0)
				newInitializer= createPlaceholderForExpression(oldFieldFragment.getInitializer(), field.getCompilationUnit(), mapping, rewrite);
			else
				newInitializer= createPlaceholderForExpression(oldFieldFragment.getInitializer(), field.getCompilationUnit(), rewrite);
			newFragment.setInitializer(newInitializer);
		}
		newFragment.setName(((SimpleName) ASTNode.copySubtree(rewrite.getAST(), oldFieldFragment.getName())));
		final FieldDeclaration newField= rewrite.getAST().newFieldDeclaration(newFragment);
		final FieldDeclaration oldField= ASTNodeSearchUtil.getFieldDeclarationNode(field, unit);
		copyJavadocNode(rewrite, field, oldField, newField);
		newField.modifiers().addAll(ASTNodeFactory.newModifiers(rewrite.getAST(), modifiers));
		Type oldType= oldField.getType();
		Type newType= null;
		if (mapping.length > 0) {
			newType= createPlaceholderForType(oldType, field.getCompilationUnit(), mapping, rewrite);
		} else
			newType= createPlaceholderForType(oldType, field.getCompilationUnit(), rewrite);
		newField.setType(newType);
		return newField;
	}

	protected static Expression createPlaceholderForExpression(final Expression expression, final ICompilationUnit declaringCu, final ASTRewrite rewrite) throws JavaModelException {
		return (Expression) rewrite.createStringPlaceholder(declaringCu.getBuffer().getText(expression.getStartPosition(), expression.getLength()), ASTNode.METHOD_INVOCATION);
	}

	protected static Expression createPlaceholderForExpression(final Expression expression, final ICompilationUnit declaringCu, final TypeVariableMaplet[] mapping, final ASTRewrite rewrite) throws JavaModelException {
		Expression result= null;
		try {
			final IDocument document= new Document(declaringCu.getBuffer().getContents());
			final ASTRewrite rewriter= ASTRewrite.create(expression.getAST());
			final ITrackedNodePosition position= rewriter.track(expression);
			expression.accept(new TypeVariableMapper(rewriter, mapping));
			rewriter.rewriteAST(document, declaringCu.getJavaProject().getOptions(true)).apply(document, TextEdit.NONE);
			result= (Expression) rewrite.createStringPlaceholder(document.get(position.getStartPosition(), position.getLength()), ASTNode.METHOD_INVOCATION);
		} catch (MalformedTreeException exception) {
			JavaPlugin.log(exception);
		} catch (BadLocationException exception) {
			JavaPlugin.log(exception);
		}
		return result;
	}

	protected static BodyDeclaration createPlaceholderForProtectedTypeDeclaration(final BodyDeclaration bodyDeclaration, final CompilationUnit declaringCuNode, final ICompilationUnit declaringCu, final ASTRewrite rewrite, final boolean removeIndentation) throws JavaModelException {
		String text= null;
		try {
			final ASTRewrite rewriter= ASTRewrite.create(bodyDeclaration.getAST());
			ModifierRewrite.create(rewriter, bodyDeclaration).setVisibility(Modifier.PROTECTED, null);
			final ITrackedNodePosition position= rewriter.track(bodyDeclaration);
			final IDocument document= new Document(declaringCu.getBuffer().getText(declaringCuNode.getStartPosition(), declaringCuNode.getLength()));
			rewriter.rewriteAST(document, declaringCu.getJavaProject().getOptions(true)).apply(document, TextEdit.UPDATE_REGIONS);
			text= document.get(position.getStartPosition(), position.getLength());
		} catch (BadLocationException exception) {
			text= getNewText(bodyDeclaration, declaringCu, removeIndentation);
		}
		return (BodyDeclaration) rewrite.createStringPlaceholder(text, ASTNode.TYPE_DECLARATION);
	}

	protected static BodyDeclaration createPlaceholderForProtectedTypeDeclaration(final BodyDeclaration bodyDeclaration, final CompilationUnit declaringCuNode, final ICompilationUnit declaringCu, final TypeVariableMaplet[] mapping, final ASTRewrite rewrite, final boolean removeIndentation) throws JavaModelException {
		BodyDeclaration result= null;
		try {
			final IDocument document= new Document(declaringCu.getBuffer().getContents());
			final ASTRewrite rewriter= ASTRewrite.create(bodyDeclaration.getAST());
			final ITrackedNodePosition position= rewriter.track(bodyDeclaration);
			bodyDeclaration.accept(new TypeVariableMapper(rewriter, mapping) {

				public final boolean visit(final AnnotationTypeDeclaration node) {
					ModifierRewrite.create(fRewrite, bodyDeclaration).setVisibility(Modifier.PROTECTED, null);
					return true;
				}

				public final boolean visit(final EnumDeclaration node) {
					ModifierRewrite.create(fRewrite, bodyDeclaration).setVisibility(Modifier.PROTECTED, null);
					return true;
				}

				public final boolean visit(final TypeDeclaration node) {
					ModifierRewrite.create(fRewrite, bodyDeclaration).setVisibility(Modifier.PROTECTED, null);
					return true;
				}
			});
			rewriter.rewriteAST(document, declaringCu.getJavaProject().getOptions(true)).apply(document, TextEdit.NONE);
			result= (BodyDeclaration) rewrite.createStringPlaceholder(document.get(position.getStartPosition(), position.getLength()), ASTNode.TYPE_DECLARATION);
		} catch (MalformedTreeException exception) {
			JavaPlugin.log(exception);
		} catch (BadLocationException exception) {
			JavaPlugin.log(exception);
		}
		return result;
	}

	protected static SingleVariableDeclaration createPlaceholderForSingleVariableDeclaration(final SingleVariableDeclaration declaration, final ICompilationUnit declaringCu, final ASTRewrite rewrite) throws JavaModelException {
		return (SingleVariableDeclaration) rewrite.createStringPlaceholder(declaringCu.getBuffer().getText(declaration.getStartPosition(), declaration.getLength()), ASTNode.SINGLE_VARIABLE_DECLARATION);
	}

	protected static SingleVariableDeclaration createPlaceholderForSingleVariableDeclaration(final SingleVariableDeclaration declaration, final ICompilationUnit declaringCu, final TypeVariableMaplet[] mapping, final ASTRewrite rewrite) throws JavaModelException {
		SingleVariableDeclaration result= null;
		try {
			final IDocument document= new Document(declaringCu.getBuffer().getContents());
			final ASTRewrite rewriter= ASTRewrite.create(declaration.getAST());
			final ITrackedNodePosition position= rewriter.track(declaration);
			declaration.accept(new TypeVariableMapper(rewriter, mapping));
			rewriter.rewriteAST(document, declaringCu.getJavaProject().getOptions(true)).apply(document, TextEdit.NONE);
			result= (SingleVariableDeclaration) rewrite.createStringPlaceholder(document.get(position.getStartPosition(), position.getLength()), ASTNode.SINGLE_VARIABLE_DECLARATION);
		} catch (MalformedTreeException exception) {
			JavaPlugin.log(exception);
		} catch (BadLocationException exception) {
			JavaPlugin.log(exception);
		}
		return result;
	}

	protected static Type createPlaceholderForType(final Type type, final ICompilationUnit declaringCu, final ASTRewrite rewrite) throws JavaModelException {
		return (Type) rewrite.createStringPlaceholder(declaringCu.getBuffer().getText(type.getStartPosition(), type.getLength()), ASTNode.SIMPLE_TYPE);
	}

	protected static Type createPlaceholderForType(final Type type, final ICompilationUnit declaringCu, final TypeVariableMaplet[] mapping, final ASTRewrite rewrite) throws JavaModelException {
		Type result= null;
		try {
			final IDocument document= new Document(declaringCu.getBuffer().getContents());
			final ASTRewrite rewriter= ASTRewrite.create(type.getAST());
			final ITrackedNodePosition position= rewriter.track(type);
			type.accept(new TypeVariableMapper(rewriter, mapping));
			rewriter.rewriteAST(document, declaringCu.getJavaProject().getOptions(true)).apply(document, TextEdit.NONE);
			result= (Type) rewrite.createStringPlaceholder(document.get(position.getStartPosition(), position.getLength()), ASTNode.SIMPLE_TYPE);
		} catch (MalformedTreeException exception) {
			JavaPlugin.log(exception);
		} catch (BadLocationException exception) {
			JavaPlugin.log(exception);
		}
		return result;
	}

	protected static BodyDeclaration createPlaceholderForTypeDeclaration(final BodyDeclaration bodyDeclaration, final ICompilationUnit declaringCu, final ASTRewrite rewrite, final boolean removeIndentation) throws JavaModelException {
		return (BodyDeclaration) rewrite.createStringPlaceholder(getNewText(bodyDeclaration, declaringCu, removeIndentation), ASTNode.TYPE_DECLARATION);
	}

	protected static BodyDeclaration createPlaceholderForTypeDeclaration(final BodyDeclaration bodyDeclaration, final ICompilationUnit declaringCu, final TypeVariableMaplet[] mapping, final ASTRewrite rewrite, final boolean removeIndentation) throws JavaModelException {
		BodyDeclaration result= null;
		try {
			final IDocument document= new Document(declaringCu.getBuffer().getContents());
			final ASTRewrite rewriter= ASTRewrite.create(bodyDeclaration.getAST());
			final ITrackedNodePosition position= rewriter.track(bodyDeclaration);
			bodyDeclaration.accept(new TypeVariableMapper(rewriter, mapping));
			rewriter.rewriteAST(document, declaringCu.getJavaProject().getOptions(true)).apply(document, TextEdit.NONE);
			result= (BodyDeclaration) rewrite.createStringPlaceholder(document.get(position.getStartPosition(), position.getLength()), ASTNode.TYPE_DECLARATION);
		} catch (MalformedTreeException exception) {
			JavaPlugin.log(exception);
		} catch (BadLocationException exception) {
			JavaPlugin.log(exception);
		}
		return result;
	}

	protected static String createTypeLabel(final IType type) {
		return JavaModelUtil.getFullyQualifiedName(type);
	}

	protected static void deleteDeclarationNodes(final CompilationUnitRewrite sourceRewriter, final boolean sameCu, final CompilationUnitRewrite unitRewriter, final List<IMember> members) throws JavaModelException {
		final List<ASTNode> declarationNodes= getDeclarationNodes(unitRewriter.getRoot(), members);
		for (final Iterator<ASTNode> iterator= declarationNodes.iterator(); iterator.hasNext();) {
			final ASTNode node= iterator.next();
			final ASTRewrite rewriter= unitRewriter.getASTRewrite();
			final ImportRemover remover= unitRewriter.getImportRemover();
			if (node instanceof VariableDeclarationFragment) {
				if (node.getParent() instanceof FieldDeclaration) {
					final FieldDeclaration declaration= (FieldDeclaration) node.getParent();
					if (areAllFragmentsDeleted(declaration, declarationNodes)) {
						rewriter.remove(declaration, unitRewriter.createGroupDescription(RefactoringCoreMessages.HierarchyRefactoring_remove_member)); 
						if (!sameCu)
							remover.registerRemovedNode(declaration);
					} else {
						rewriter.remove(node, unitRewriter.createGroupDescription(RefactoringCoreMessages.HierarchyRefactoring_remove_member)); 
						if (!sameCu)
							remover.registerRemovedNode(node);
					}
				}
			} else {
				rewriter.remove(node, unitRewriter.createGroupDescription(RefactoringCoreMessages.HierarchyRefactoring_remove_member)); 
				if (!sameCu)
					remover.registerRemovedNode(node);
			}
		}
	}

	protected static List<ASTNode> getDeclarationNodes(final CompilationUnit cuNode, final List<IMember> members) throws JavaModelException {
		final List<ASTNode> result= new ArrayList<ASTNode>(members.size());
		for (final Iterator<IMember> iterator= members.iterator(); iterator.hasNext();) {
			final IMember member= iterator.next();
			ASTNode node= null;
			if (member instanceof IField) {
				if (Flags.isEnum(member.getFlags()))
					node= ASTNodeSearchUtil.getEnumConstantDeclaration((IField) member, cuNode);
				else
					node= ASTNodeSearchUtil.getFieldDeclarationFragmentNode((IField) member, cuNode);
			} else if (member instanceof IType)
				node= ASTNodeSearchUtil.getAbstractTypeDeclarationNode((IType) member, cuNode);
			else if (member instanceof IMethod)
				node= ASTNodeSearchUtil.getMethodDeclarationNode((IMethod) member, cuNode);
			if (node != null)
				result.add(node);
		}
		return result;
	}

	protected static String getNewText(final ASTNode node, final ICompilationUnit declaringCu, final boolean removeIndentation) throws JavaModelException {
		final String result= declaringCu.getBuffer().getText(node.getStartPosition(), node.getLength());
		if (removeIndentation)
			return getUnindentedText(result, declaringCu);

		return result;
	}

	protected static String getUnindentedText(final String text, final ICompilationUnit declaringCu) throws JavaModelException {
		final String[] lines= Strings.convertIntoLines(text);
		Strings.trimIndentation(lines, declaringCu.getJavaProject(), false);
		return Strings.concatenate(lines, StubUtility.getLineDelimiterUsed(declaringCu));
	}

	protected final Map<IMember, Object[]> fCachedMembersReferences= new HashMap<IMember, Object[]>(2);

	protected IType[] fCachedReferencedTypes;

	protected TextChangeManager fChangeManager;

	protected IType fDeclaringType;

	protected IMember[] fMembersToMove;

	protected HierarchyRefactoring(final IMember[] members) {
		Assert.isNotNull(members);
		fMembersToMove= (IMember[]) SourceReferenceUtil.sortByOffset(members);
	}

	protected boolean canBeAccessedFrom(final IMember member, final IType target, final ITypeHierarchy hierarchy) throws JavaModelException {
		Assert.isTrue(!(member instanceof IInitializer));

		return member.exists();
	}

	protected RefactoringStatus checkDeclaringType(final IProgressMonitor monitor) throws JavaModelException {
		final IType type= getDeclaringType();
		if (type.isEnum())
			return RefactoringStatus.createFatalErrorStatus(RefactoringCoreMessages.HierarchyRefactoring_enum_members); 
		if (type.isAnnotation())
			return RefactoringStatus.createFatalErrorStatus(RefactoringCoreMessages.HierarchyRefactoring_annotation_members); 
		if (type.isInterface())
			return RefactoringStatus.createFatalErrorStatus(RefactoringCoreMessages.HierarchyRefactoring_interface_members); 
		if (type.isBinary())
			return RefactoringStatus.createFatalErrorStatus(RefactoringCoreMessages.HierarchyRefactoring_members_of_binary); 
		if (type.isReadOnly())
			return RefactoringStatus.createFatalErrorStatus(RefactoringCoreMessages.HierarchyRefactoring_members_of_read_only); 
		return new RefactoringStatus();
	}

	protected RefactoringStatus checkIfMembersExist() {
		RefactoringStatus result= new RefactoringStatus();
		IMember member= null;
		for (int index= 0; index < fMembersToMove.length; index++) {
			member= fMembersToMove[index];
			if (member == null || !member.exists())
				result.addFatalError(RefactoringCoreMessages.HierarchyRefactoring_does_not_exist); 
		}
		return result;
	}

	protected void clearCaches() {
		fCachedReferencedTypes= null;
	}

	protected void copyParameters(final ASTRewrite rewrite, final ICompilationUnit unit, final MethodDeclaration oldMethod, final MethodDeclaration newMethod, final TypeVariableMaplet[] mapping) throws JavaModelException {
		SingleVariableDeclaration newDeclaration= null;
		for (int index= 0, size= oldMethod.parameters().size(); index < size; index++) {
			SingleVariableDeclaration oldDeclaration= (SingleVariableDeclaration) oldMethod.parameters().get(index);
			if (mapping.length > 0)
				newDeclaration= createPlaceholderForSingleVariableDeclaration(oldDeclaration, unit, mapping, rewrite);
			else
				newDeclaration= createPlaceholderForSingleVariableDeclaration(oldDeclaration, unit, rewrite);
			newMethod.parameters().add(index, newDeclaration);
		}
	}

	protected void copyReturnType(final ASTRewrite rewrite, final ICompilationUnit unit, final MethodDeclaration oldMethod, final MethodDeclaration newMethod, final TypeVariableMaplet[] mapping) throws JavaModelException {
		Type newReturnType= null;
		if (mapping.length > 0)
			newReturnType= createPlaceholderForType(oldMethod.getReturnType2(), unit, mapping, rewrite);
		else
			newReturnType= createPlaceholderForType(oldMethod.getReturnType2(), unit, rewrite);
		newMethod.setReturnType2(newReturnType);
	}

	public IType getDeclaringType() {
		if (fDeclaringType != null)
			return fDeclaringType;
		fDeclaringType= (IType) WorkingCopyUtil.getOriginal(fMembersToMove[0].getDeclaringType());
		return fDeclaringType;
	}

	public IMember[] getMembersToMove() {
		return fMembersToMove;
	}

	protected IType[] getTypesReferencedInMovedMembers(final IProgressMonitor monitor) throws JavaModelException {
		if (fCachedReferencedTypes == null) {
			final IType[] types= ReferenceFinderUtil.getTypesReferencedIn(fMembersToMove, monitor);
			final List<IType> result= new ArrayList<IType>(types.length);
			final List<IMember> members= Arrays.asList(fMembersToMove);
			for (int index= 0; index < types.length; index++) {
				if (!members.contains(types[index]) && types[index] != getDeclaringType())
					result.add(types[index]);
			}
			fCachedReferencedTypes= new IType[result.size()];
			result.toArray(fCachedReferencedTypes);
		}
		return fCachedReferencedTypes;
	}

	protected boolean hasNonMovedReferences(final IMember member, final IProgressMonitor monitor, final RefactoringStatus status) throws JavaModelException {
		if (!fCachedMembersReferences.containsKey(member)) {
			final RefactoringSearchEngine2 engine= new RefactoringSearchEngine2(SearchPattern.createPattern(member, IJavaSearchConstants.REFERENCES, SearchUtils.GENERICS_AGNOSTIC_MATCH_RULE));
			engine.setFiltering(true, true);
			engine.setStatus(status);
			engine.setScope(RefactoringScopeFactory.create(member));
			engine.searchPattern(new SubProgressMonitor(monitor, 1));
			fCachedMembersReferences.put(member, engine.getResults());
		}
		final SearchResultGroup[] groups= (SearchResultGroup[]) fCachedMembersReferences.get(member);
		if (groups.length == 0)
			return false;
		else if (groups.length > 1)
			return true;
		final ICompilationUnit unit= groups[0].getCompilationUnit();
		if (!getDeclaringType().getCompilationUnit().equals(unit))
			return true;
		final SearchMatch[] matches= groups[0].getSearchResults();
		for (int index= 0; index < matches.length; index++) {
			if (!isMovedReference(matches[index]))
				return true;
		}
		return false;
	}

	protected boolean isMovedReference(final SearchMatch match) throws JavaModelException {
		ISourceRange range= null;
		for (int index= 0; index < fMembersToMove.length; index++) {
			range= fMembersToMove[index].getSourceRange();
			if (range.getOffset() <= match.getOffset() && range.getOffset() + range.getLength() >= match.getOffset())
				return true;
		}
		return false;
	}

	protected boolean needsVisibilityAdjustment(final IMember member, final boolean references, final IProgressMonitor monitor, RefactoringStatus status) throws JavaModelException {
		if (JdtFlags.isPublic(member) || JdtFlags.isProtected(member))
			return false;
		if (!references)
			return true;
		return hasNonMovedReferences(member, monitor, status);
	}
}

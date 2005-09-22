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
package org.eclipse.jdt.internal.corext.refactoring.rename;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.OperationCanceledException;
import org.eclipse.core.runtime.SubProgressMonitor;

import org.eclipse.jface.util.Assert;

import org.eclipse.jdt.core.IMember;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IRegion;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.ITypeHierarchy;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.core.WorkingCopyOwner;
import org.eclipse.jdt.core.search.IJavaSearchConstants;
import org.eclipse.jdt.core.search.IJavaSearchScope;
import org.eclipse.jdt.core.search.SearchMatch;
import org.eclipse.jdt.core.search.SearchPattern;

import org.eclipse.jdt.internal.corext.refactoring.RefactoringScopeFactory;
import org.eclipse.jdt.internal.corext.refactoring.RefactoringSearchEngine2;
import org.eclipse.jdt.internal.corext.util.JavaModelUtil;

public class RippleMethodFinder2 {
	
	private final IMethod fMethod;
	private List/*<IMethod>*/<IMethod> fDeclarations;
	private ITypeHierarchy fHierarchy;
	private Map/*IType, IMethod*/<IType, IMethod> fTypeToMethod;
	private Set/*IType*/<IType> fRootTypes;
	private MultiMap/*IType, IType*/ fRootReps;
	private Map/*IType, ITypeHierarchy*/<IType, ITypeHierarchy> fRootHierarchies;
	private UnionFind fUnionFind;

	private static class MultiMap {
		HashMap/*<IType, Collection>*/<IType, Collection> fImplementation= new HashMap<IType, Collection>();

		public void put(IType key, IType value) {
			Collection<IType> collection= fImplementation.get(key);
			if (collection == null) {
				collection= new HashSet<IType>();
				fImplementation.put(key, collection);
			}
			collection.add(value);
		}
		
		public Collection get(IType key) {
			return fImplementation.get(key);
		}
	}
	private static class UnionFind {
		HashMap/*<IType, IType>*/<IType, IType> fElementToRepresentative= new HashMap<IType, IType>();
		
		public void init(IType type) {
			fElementToRepresentative.put(type, type);
		}
		
		//path compression:
		public IType find(IType element) {
			IType root= element;
			IType rep= fElementToRepresentative.get(root);
			while (rep != null && ! rep.equals(root)) {
				root= rep;
				rep= fElementToRepresentative.get(root);
			}
			if (rep == null)
				return null;

			rep= fElementToRepresentative.get(element);
			while (! rep.equals(root)) {
				IType temp= element;
				element= rep;
				fElementToRepresentative.put(temp, root);
				rep= fElementToRepresentative.get(element);
			}
			return root;
		}
		
//		//straightforward:
//		public IType find(IType element) {
//			IType current= element;
//			IType rep= (IType) fElementToRepresentative.get(current);
//			while (rep != null && ! rep.equals(current)) {
//				current= rep;
//				rep= (IType) fElementToRepresentative.get(current);
//			}
//			if (rep == null)
//				return null;
//			else
//				return current;
//		}
		
		public void union(IType rep1, IType rep2) {
			fElementToRepresentative.put(rep1, rep2);
		}
	}
	
	
	private RippleMethodFinder2(IMethod method){
		fMethod= method;
	}
	
	public static IMethod[] getRelatedMethods(IMethod method, IProgressMonitor pm, WorkingCopyOwner owner) throws CoreException {
		try{
			if (! MethodChecks.isVirtual(method))
				return new IMethod[]{ method };
			
			return new RippleMethodFinder2(method).getAllRippleMethods(pm, owner);
		} finally{
			pm.done();
		}	
	}
	
	private IMethod[] getAllRippleMethods(IProgressMonitor pm, WorkingCopyOwner owner) throws CoreException {
		pm.beginTask("", 4); //$NON-NLS-1$
		
		findAllDeclarations(new SubProgressMonitor(pm, 1), owner);
		//TODO: report binary methods to an error status
		//TODO: report assertion as error status and fall back to only return fMethod
		//check for bug 81058: 
		Assert.isTrue(fDeclarations.contains(fMethod), "Search for method declaration did not find original element"); //$NON-NLS-1$
		
		createHierarchyOfDeclarations(new SubProgressMonitor(pm, 1), owner);
		createTypeToMethod();
		createUnionFind();
		if (pm.isCanceled())
			throw new OperationCanceledException();

		fHierarchy= null;
		fRootTypes= null;

		Map/*IType, List<IType>*/<IType, List> partitioning= new HashMap<IType, List>();
		for (Iterator<IType> iter= fTypeToMethod.keySet().iterator(); iter.hasNext();) {
			IType type= iter.next();
			IType rep= fUnionFind.find(type);
			List/*<IType>*/<IType> types= partitioning.get(rep);
			if (types == null)
				types= new ArrayList<IType>();
			types.add(type);
			partitioning.put(rep, types);
		}
		Assert.isTrue(partitioning.size() > 0);
		if (partitioning.size() == 1)
			return fDeclarations.toArray(new IMethod[fDeclarations.size()]);
		
		//Multiple partititions; must look out for nasty marriage cases
		//(types inheriting method from two ancestors, but without redeclaring it).
		IType methodTypeRep= fUnionFind.find(fMethod.getDeclaringType());
		List/*<IType>*/ relatedTypes= partitioning.get(methodTypeRep);
		boolean hasRelatedInterfaces= false;
		List/*<IMethod>*/<IMethod> relatedMethods= new ArrayList<IMethod>();
		for (Iterator iter= relatedTypes.iterator(); iter.hasNext();) {
			IType relatedType= (IType) iter.next();
			relatedMethods.add(fTypeToMethod.get(relatedType));
			if (relatedType.isInterface())
				hasRelatedInterfaces= true;
		}
		
		//Definition: An alien type is a type that is not a related type. The set of
		// alien types diminishes as new types become related (aka marry a relatedType).
		
		List/*<IMethod>*/<IMethod> alienDeclarations= new ArrayList<IMethod>(fDeclarations);
		fDeclarations= null;
		alienDeclarations.removeAll(relatedMethods);
		List/*<IType>*/<IType> alienTypes= new ArrayList<IType>();
		boolean hasAlienInterfaces= false;
		for (Iterator<IMethod> iter= alienDeclarations.iterator(); iter.hasNext();) {
			IMethod alienDeclaration= iter.next();
			IType alienType= alienDeclaration.getDeclaringType();
			alienTypes.add(alienType);
			if (alienType.isInterface())
				hasAlienInterfaces= true;
		}
		if (alienTypes.size() == 0) //no nasty marriage scenarios without types to marry with...
			return relatedMethods.toArray(new IMethod[relatedMethods.size()]);
		if (! hasRelatedInterfaces && ! hasAlienInterfaces) //no nasty marriage scenarios without interfaces...
			return relatedMethods.toArray(new IMethod[relatedMethods.size()]);
		
		//find all subtypes of related types:
		HashSet/*<IType>*/<IType> relatedSubTypes= new HashSet<IType>();
		List/*<IType>*/ relatedTypesToProcess= new ArrayList(relatedTypes);
		while (relatedTypesToProcess.size() > 0) {
			//TODO: would only need subtype hierarchies of all top-of-ripple relatedTypesToProcess
			for (Iterator iter= relatedTypesToProcess.iterator(); iter.hasNext();) {
				if (pm.isCanceled())
					throw new OperationCanceledException();
				IType relatedType= (IType) iter.next();
				ITypeHierarchy hierarchy= getCachedHierarchy(relatedType, owner, new SubProgressMonitor(pm, 1));
				if (hierarchy == null)
					hierarchy= relatedType.newTypeHierarchy(owner, new SubProgressMonitor(pm, 1));
				IType[] allSubTypes= hierarchy.getAllSubtypes(relatedType);
				for (int i= 0; i < allSubTypes.length; i++)
					relatedSubTypes.add(allSubTypes[i]);
			}
			relatedTypesToProcess.clear(); //processed; make sure loop terminates
			
			HashSet/*<IType>*/<IType> marriedAlienTypeReps= new HashSet<IType>();
			for (Iterator<IType> iter= alienTypes.iterator(); iter.hasNext();) {
				if (pm.isCanceled())
					throw new OperationCanceledException();
				IType alienType= iter.next();
				IMethod alienMethod= fTypeToMethod.get(alienType);
				ITypeHierarchy hierarchy= getCachedHierarchy(alienType, owner, new SubProgressMonitor(pm, 1));
				if (hierarchy == null)
					hierarchy= alienType.newTypeHierarchy(owner, new SubProgressMonitor(pm, 1));
				IType[] allSubtypes= hierarchy.getAllSubtypes(alienType);
				for (int i= 0; i < allSubtypes.length; i++) {
					IType subtype= allSubtypes[i];
					if (relatedSubTypes.contains(subtype)) {
						if (JavaModelUtil.isVisibleInHierarchy(alienMethod, subtype.getPackageFragment())) {
							marriedAlienTypeReps.add(fUnionFind.find(alienType));
						} else {
							// not overridden
						}
					}
				}
			}
			
			if (marriedAlienTypeReps.size() == 0)
				return relatedMethods.toArray(new IMethod[relatedMethods.size()]);
			
			for (Iterator<IType> iter= marriedAlienTypeReps.iterator(); iter.hasNext();) {
				IType marriedAlienTypeRep= iter.next();
				List/*<IType>*/ marriedAlienTypes= partitioning.get(marriedAlienTypeRep);
				for (Iterator iterator= marriedAlienTypes.iterator(); iterator.hasNext();) {
					IType marriedAlienInterfaceType= (IType) iterator.next();
					relatedMethods.add(fTypeToMethod.get(marriedAlienInterfaceType));
				}
				alienTypes.removeAll(marriedAlienTypes); //not alien any more
				relatedTypesToProcess.addAll(marriedAlienTypes); //process freshly married types again
			}
		}

		fRootReps= null;
		fRootHierarchies= null;
		fTypeToMethod= null;
		fUnionFind= null;

		return relatedMethods.toArray(new IMethod[relatedMethods.size()]);
	}

	private ITypeHierarchy getCachedHierarchy(IType type, WorkingCopyOwner owner, IProgressMonitor monitor) throws JavaModelException {
		IType rep= fUnionFind.find(type);
		if (rep != null) {
			Collection collection= fRootReps.get(rep);
			for (Iterator iter= collection.iterator(); iter.hasNext();) {
				IType root= (IType) iter.next();
				ITypeHierarchy hierarchy= fRootHierarchies.get(root);
				if (hierarchy == null) {
					hierarchy= root.newTypeHierarchy(owner, new SubProgressMonitor(monitor, 1));
					fRootHierarchies.put(root, hierarchy);
				}
				if (hierarchy.contains(type))
					return hierarchy;
			}
		}
		return null;
	}

	private void findAllDeclarations(IProgressMonitor monitor, WorkingCopyOwner owner) throws CoreException {
		fDeclarations= new ArrayList<IMethod>();
		final RefactoringSearchEngine2 engine= new RefactoringSearchEngine2(SearchPattern.createPattern(fMethod, IJavaSearchConstants.DECLARATIONS | IJavaSearchConstants.IGNORE_DECLARING_TYPE | IJavaSearchConstants.IGNORE_RETURN_TYPE, SearchPattern.R_ERASURE_MATCH | SearchPattern.R_CASE_SENSITIVE));
		if (owner != null)
			engine.setOwner(owner);
		engine.setScope(RefactoringScopeFactory.createRelatedProjectsScope(fMethod.getJavaProject(), IJavaSearchScope.SOURCES | IJavaSearchScope.APPLICATION_LIBRARIES | IJavaSearchScope.SYSTEM_LIBRARIES));
		engine.setFiltering(true, true);
		engine.setGrouping(false);
		engine.searchPattern(new SubProgressMonitor(monitor, 1));
		final SearchMatch[] matches= (SearchMatch[]) engine.getResults();
		IMethod method= null;
		for (int index= 0; index < matches.length; index++) {
			method= (IMethod) matches[index].getElement();
			if (method != null)
				fDeclarations.add(method);
		}
	}

	private void createHierarchyOfDeclarations(IProgressMonitor pm, WorkingCopyOwner owner) throws JavaModelException {
		IRegion region= JavaCore.newRegion();
		for (Iterator<IMethod> iter= fDeclarations.iterator(); iter.hasNext();) {
			IType declaringType= iter.next().getDeclaringType();
			region.add(declaringType);
		}
		fHierarchy= JavaCore.newTypeHierarchy(region, owner, pm);
	}
	
	private void createTypeToMethod() {
		fTypeToMethod= new HashMap<IType, IMethod>();
		for (Iterator<IMethod> iter= fDeclarations.iterator(); iter.hasNext();) {
			IMethod declaration= iter.next();
			fTypeToMethod.put(declaration.getDeclaringType(), declaration);
		}
	}

	private void createUnionFind() throws JavaModelException {
		fRootTypes= new HashSet<IType>(fTypeToMethod.keySet());
		fUnionFind= new UnionFind();
		for (Iterator<IType> iter= fTypeToMethod.keySet().iterator(); iter.hasNext();) {
			IType type= iter.next();
			fUnionFind.init(type);
		}
		for (Iterator<IType> iter= fTypeToMethod.keySet().iterator(); iter.hasNext();) {
			IType type= iter.next();
			uniteWithSupertypes(type, type);
		}
		fRootReps= new MultiMap();
		for (Iterator<IType> iter= fRootTypes.iterator(); iter.hasNext();) {
			IType type= iter.next();
			IType rep= fUnionFind.find(type);
			if (rep != null)
				fRootReps.put(rep, type);
		}
		fRootHierarchies= new HashMap<IType, ITypeHierarchy>();
	}

	private void uniteWithSupertypes(IType anchor, IType type) throws JavaModelException {
		IType[] supertypes= fHierarchy.getSupertypes(type);
		for (int i= 0; i < supertypes.length; i++) {
			IType supertype= supertypes[i];
			IType superRep= fUnionFind.find(supertype);
			if (superRep == null) {
				//Type doesn't declare method, but maybe supertypes?
				uniteWithSupertypes(anchor, supertype);
			} else {
				//check whether method in supertype is really overridden:
				IMember superMethod= fTypeToMethod.get(supertype);
				if (JavaModelUtil.isVisibleInHierarchy(superMethod, anchor.getPackageFragment())) {
					IType rep= fUnionFind.find(anchor);
					fUnionFind.union(rep, superRep);
					// current type is no root anymore
					fRootTypes.remove(anchor);
					uniteWithSupertypes(supertype, supertype);
				} else {
					//Not overridden -> overriding chain ends here.
				}
			}
		}
	}	
}
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
package org.eclipse.jdt.internal.corext.dom;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;

import org.eclipse.text.edits.CopySourceEdit;
import org.eclipse.text.edits.CopyTargetEdit;
import org.eclipse.text.edits.DeleteEdit;
import org.eclipse.text.edits.InsertEdit;
import org.eclipse.text.edits.MoveSourceEdit;
import org.eclipse.text.edits.MoveTargetEdit;
import org.eclipse.text.edits.RangeMarker;
import org.eclipse.text.edits.ReplaceEdit;
import org.eclipse.text.edits.TextEdit;

import org.eclipse.core.runtime.CoreException;

import org.eclipse.jdt.core.compiler.ITerminalSymbols;
import org.eclipse.jdt.core.dom.*;

import org.eclipse.jdt.internal.corext.Assert;
import org.eclipse.jdt.internal.corext.dom.ASTRewrite.CopyPlaceholderData;
import org.eclipse.jdt.internal.corext.dom.ASTRewrite.MovePlaceholderData;
import org.eclipse.jdt.internal.corext.dom.ASTRewrite.StringPlaceholderData;
import org.eclipse.jdt.internal.corext.dom.ASTRewriteFormatter.BlockContext;
import org.eclipse.jdt.internal.corext.dom.ASTRewriteFormatter.NodeMarker;
import org.eclipse.jdt.internal.corext.dom.ASTRewriteFormatter.Prefix;
import org.eclipse.jdt.internal.corext.textmanipulation.GroupDescription;
import org.eclipse.jdt.internal.corext.textmanipulation.TextBuffer;
import org.eclipse.jdt.internal.corext.util.CodeFormatterUtil;
import org.eclipse.jdt.internal.corext.util.Strings;
import org.eclipse.jdt.internal.ui.JavaPlugin;

/**
 * Infrastructure to support code modifications. Existing code must stay untouched, new code
 * added with correct formatting, moved code left with the users formattings / comments.
 * Idea:
 * - Get the AST for existing code 
 * - Inserting new nodes or mark existing nodes as replaced, removed, copied or moved.
 * - This visitor analyses the changes or annotations and generates text edits
 * (text manipulation API) that describe the required code changes. See test
 * cases in org.eclipse.jdt.ui.tests / package org.eclipse.jdt.ui.tests.
 * astrewrite for examples
 */
public class ASTRewriteAnalyzer extends ASTVisitor {
	
	private TextEdit fCurrentEdit;
	private TokenScanner fTokenScanner; // shared scanner
	private HashMap fCopySources;
	private HashMap fMoveSources;
	
	final TextBuffer fTextBuffer;
	private final ASTRewrite fRewrite;
	
	private final ASTRewriteFormatter fFormatter;
	
	/**
	 * Constructor for ASTChangeAnalyzer.
	 */
	public ASTRewriteAnalyzer(TextBuffer textBuffer, TextEdit rootEdit, ASTNode rootNode, ASTRewrite rewrite) {
		fTextBuffer= textBuffer;
		fTokenScanner= null;
		fRewrite= rewrite;
		fCurrentEdit= rootEdit;
		fCopySources= new HashMap();
		fMoveSources= new HashMap();
		
		fFormatter= new ASTRewriteFormatter(rewrite, textBuffer.getLineDelimiter());
		
		ASTNodes.annotateExtraRanges(rootNode, getScanner());
	}
		
	final TokenScanner getScanner() {
		if (fTokenScanner == null) {
			fTokenScanner= new TokenScanner(fTextBuffer);
		}
		return fTokenScanner;
	}
	
	final int getExtendedOffset(ASTNode node) {
		return ASTNodes.getExtendedOffset(node);
	}
	
	final int getExtendedLength(ASTNode node) {
		return ASTNodes.getExtendedLength(node);
	}
	
	final int getExtendedEnd(ASTNode node) {
		return ASTNodes.getExtendedEnd(node);
	}
				
	final private CopySourceEdit[] getCopySources(ASTNode node) {
		int count= fRewrite.getCopyCount(node);
		if (count == 0) {
			return null;
		}
		CopySourceEdit[] edits= (CopySourceEdit[]) fCopySources.get(node);
		if (edits == null) {
			int offset= getExtendedOffset(node);
			int length= getExtendedLength(node);
			edits= new CopySourceEdit[count];
			for (int i= 0; i < count; i++) {
				edits[i]= new CopySourceEdit(offset, length);
			}
			fCopySources.put(node, edits);
		}
		return edits;
	}
	
	final private MoveSourceEdit getMoveSource(ASTNode node) {
		if (isMoveSource(node)) {
			MoveSourceEdit edit= (MoveSourceEdit) fMoveSources.get(node);
			if (edit == null) {
				int offset= getExtendedOffset(node);
				int length= getExtendedLength(node);
				edit= new MoveSourceEdit(offset, length);
			}
			fMoveSources.put(node, edit);
			return edit;
		}
		return null;
	}
	
	private final int getChangeKind(ASTNode node, int property) {
		RewriteEvent event= getEvent(node, property);
		if (event != null) {
			return event.getChangeKind();
		}
		return RewriteEvent.UNCHANGED;
	}
	
	private final boolean hasChildrenChanges(ASTNode node) {
		return fRewrite.hasChildrenChanges(node);
	}
	
	private boolean visitChildrenNeeded(ASTNode node) {
		return true; // TODO: Check if changes in range
	}
		
	private final  boolean isChanged(ASTNode node, int property) {
		RewriteEvent event= getEvent(node, property);
		if (event != null) {
			return event.getChangeKind() != RewriteEvent.UNCHANGED;
		}
		return false;
	}
	
	private final boolean isInsertBoundToPrevious(ASTNode node) {
		return fRewrite.isInsertBoundToPrevious(node);
	}	
		
	private final boolean isMoveSource(ASTNode node) {
		return fRewrite.isMoveSource(node);
	}	
		
	private final GroupDescription getDescription(ASTNode parent, int property) {
		RewriteEvent event= getEvent(parent, property);
		if (event != null) {
			return getDescription(event);
		}
		return null;
	}
	
	// new
	
	private final RewriteEvent getEvent(ASTNode parent, int property) {
		return fRewrite.getEvent(parent, property);
	}
	private final GroupDescription getDescription(RewriteEvent change) {
		return fRewrite.getDescription(change);
	}
	
	private final Object getOriginalValue(ASTNode parent, int property) {
		return fRewrite.getOriginalValue(parent, property);
	}
	
	private final Object getNewValue(ASTNode parent, int property) {
		return fRewrite.getNewValue(parent, property);
	}	
	
	private final void addEdit(TextEdit edit) {
		fCurrentEdit.addChild(edit);
	}
	
	
	private final String getLineDelimiter() {
		return fTextBuffer.getLineDelimiter();
	}
	
	private final void doTextInsert(int offset, String insertString, GroupDescription description) {
		if (insertString.length() > 0) {
			TextEdit edit= new InsertEdit(offset, insertString);
			addEdit(edit);
			if (description != null) {
				addDescription(description, edit);
			}
		}
	}
	
	private final void addDescription(GroupDescription groupDesc, TextEdit edit) {
		groupDesc.addTextEdit(edit);
	}

	
	private final TextEdit doTextRemove(int offset, int len, GroupDescription description) {
		if (len == 0) {
			return null;
		}
		TextEdit edit= new DeleteEdit(offset, len);
		addEdit(edit);
		if (description != null) {
			addDescription(description, edit);
		}
		return edit;
	}
	
	private final void doTextRemoveAndVisit(int offset, int len, ASTNode node, GroupDescription description) {
		TextEdit edit= doTextRemove(offset, len, description);
		if (edit != null) {
			fCurrentEdit= edit;
			doVisit(node);
			fCurrentEdit= edit.getParent();
		}
	}
	
	private final int doVisit(ASTNode node) {
		node.accept(this);
		return node.getStartPosition() + node.getLength();
	}
	
	private final int doVisit(ASTNode parent, int property, int offset) {
		Object node= getOriginalValue(parent, property);
		if (node instanceof ASTNode) {
			return doVisit((ASTNode) node);
		} else if (node instanceof List) {
			int endPos= offset;
			for (Iterator iter= ((List) node).iterator(); iter.hasNext();) {
				ASTNode curr= ((ASTNode) iter.next());
				endPos= doVisit(curr);
			}
			return endPos;		
		}
		return offset;
	}	
	
	
	private final void doTextReplace(int offset, int len, String insertString, GroupDescription description) {
		if (len > 0 || insertString.length() > 0) {
			TextEdit edit= new ReplaceEdit(offset, len, insertString);
			addEdit(edit);
			if (description != null) {
				addDescription(description, edit);
			}
		}
	}
		
	private final TextEdit doTextCopy(ASTNode copiedNode, int destOffset, int sourceIndentLevel, String destIndentString, int tabWidth, GroupDescription description) {
		CopySourceEdit[] edits= getCopySources(copiedNode);
		if (edits == null) {
			Assert.isTrue(false, "Copy source not annotated" + copiedNode.toString()); //$NON-NLS-1$
		}
		CopySourceEdit sourceEdit= null;
		for (int i= 0; i < edits.length; i++) {
			if (edits[i].getSourceModifier() == null) {
				sourceEdit= edits[i];
				break;
			}
		}
		if (sourceEdit == null) {
			Assert.isTrue(false, "No copy source available" + copiedNode.toString()); //$NON-NLS-1$
		}

		sourceEdit.setSourceModifier(SourceModifier.createCopyModifier(sourceIndentLevel, destIndentString, tabWidth));
	
		CopyTargetEdit targetEdit= new CopyTargetEdit(destOffset, sourceEdit);
		addEdit(targetEdit);
	
		if (description != null) {
			addDescription(description, sourceEdit);
			addDescription(description, targetEdit);
		}
		return targetEdit;			

	}
	
	private final TextEdit doTextMove(ASTNode movedNode, int destOffset, int sourceIndentLevel, String destIndentString, int tabWidth, GroupDescription description) {
		MoveSourceEdit moveEdit= getMoveSource(movedNode);
		if (moveEdit.getSourceModifier() != null) {
			Assert.isTrue(false, "No move source available" + movedNode.toString()); //$NON-NLS-1$
		}
		
		moveEdit.setSourceModifier(SourceModifier.createMoveModifier(sourceIndentLevel, destIndentString, tabWidth));
	
		MoveTargetEdit targetEdit= new MoveTargetEdit(destOffset, moveEdit);
		addEdit(targetEdit);
	
		if (description != null) {
			addDescription(description, moveEdit);
			addDescription(description, targetEdit);
		}
		return targetEdit;			

	}	
	
			
	private void changeNotSupported(ASTNode node) {
		Assert.isTrue(false, "Change not supported in " + node.getClass().getName()); //$NON-NLS-1$
	}
	
	
	private class ListRewriter {
		protected String fContantSeparator;
		protected int fStartPos;
		
		protected RewriteEvent[] fList;
		
		protected final ASTNode getOriginalNode(int index) {
			return (ASTNode) fList[index].getOriginalValue();
		}
		
		protected String getSeparatorString(int nodeIndex) {
			return fContantSeparator;
		}
		
		protected int getInitialIndent() {
			return getIndent(fStartPos);
		}
		
		protected int getNodeIndent(int nodeIndex) {
			ASTNode node= getOriginalNode(nodeIndex);
			if (node == null) {
				for (int i= nodeIndex - 1; i>= 0; i--) {
					ASTNode curr= getOriginalNode(i);
					if (curr != null) {
						return getIndent(curr.getStartPosition());
					}
				}
				return getInitialIndent();
			}
			return getIndent(node.getStartPosition());
		}
		
		protected int getStartOfNextNode(int nextIndex, int defaultPos) {
			for (int i= nextIndex; i < fList.length; i++) {
				RewriteEvent elem= fList[i];
				if (elem.getChangeKind() != RewriteEvent.INSERTED) {
					ASTNode node= (ASTNode) elem.getOriginalValue();
					return getExtendedOffset(node);
				}
			}
			return defaultPos;
		}
		
		protected int getEndOfNode(ASTNode node) {
			return getExtendedEnd(node);
		}		
		
		public final int rewriteList(RewriteEvent[] listEvents, int startPos, String keyword, String separator) {
			fContantSeparator= separator;
			return rewriteList(listEvents, startPos, keyword);
		}
		
		private boolean insertAfterSeparator(ASTNode node) {
			return !isInsertBoundToPrevious(node);
		}
		
		public final int rewriteList(RewriteEvent[] listEvents, int startPos, String keyword) {
			fList= listEvents;
			fStartPos= startPos;
			
			int total= fList.length;
			if (total == 0) {
				return fStartPos;
			}
		
			int currPos= -1;
			
			int lastNonInsert= -1;
			int lastNonDelete= -1;
		
			for (int i= 0; i < total; i++) {
				int currMark= fList[i].getChangeKind();
				if (currMark != RewriteEvent.INSERTED) {
					lastNonInsert= i;
					if (currPos == -1) {
						ASTNode elem= (ASTNode) fList[i].getOriginalValue();
						currPos= getExtendedOffset(elem);
					}
				}
				if (currMark != RewriteEvent.REMOVED) {
					lastNonDelete= i;
				}			
			}
		
			if (currPos == -1) { // only inserts
				if (keyword.length() > 0) {  // creating a new list -> insert keyword first (e.g. " throws ")
					GroupDescription description= getDescription(fList[0]); // first node is insert
					doTextInsert(startPos, keyword, description);
				}
				currPos= startPos;
			}
			if (lastNonDelete == -1) { // all removed, set back to start so the keyword is removed as well
				currPos= startPos;
			}
			
			int prevEnd= currPos;
			
			final int NONE= 0, NEW= 1, EXISTING= 2;
			int separatorState= NEW;

			for (int i= 0; i < total; i++) {
				RewriteEvent currEvent= fList[i];
				int currMark= currEvent.getChangeKind();
				int nextIndex= i + 1;

				if (currMark == RewriteEvent.INSERTED) {
					GroupDescription description= getDescription(currEvent);
					ASTNode node= (ASTNode) currEvent.getNewValue();
					
					if (separatorState == NONE) { // element after last existing element (but not first)
						doTextInsert(currPos, getSeparatorString(i - 1), description);
						separatorState= NEW;
					}
					if (separatorState == NEW || insertAfterSeparator(node)) {
						doTextInsert(currPos, node, getNodeIndent(i), true, description);
						
						separatorState= NEW;
						if (i != lastNonDelete) {
							if (fList[nextIndex].getChangeKind() != RewriteEvent.INSERTED) {
								doTextInsert(currPos, getSeparatorString(i), description);
							} else {
								separatorState= NONE;
							}
						}
					} else { // EXISTING && insert before separator
						doTextInsert(prevEnd, getSeparatorString(i - 1), description);
						doTextInsert(prevEnd, node, getNodeIndent(i), true, description);
					}
				} else if (currMark == RewriteEvent.REMOVED) {
					ASTNode node= (ASTNode) currEvent.getOriginalValue();
					int currEnd= getEndOfNode(node);
					if (i > lastNonDelete && separatorState == EXISTING) {
						// is last, remove previous separator
						doTextRemoveAndVisit(prevEnd, currEnd - prevEnd, node, getDescription(currEvent));
						currPos= currEnd;
						prevEnd= currEnd;
					} else {
						// remove element and next separator
						int end= getStartOfNextNode(nextIndex, currEnd); // start of next
						doTextRemoveAndVisit(currPos, end - currPos, node, getDescription(currEvent));
						currPos= end;
						prevEnd= currEnd;
						separatorState= NEW;
					}
				} else { // replaced or unchanged
					if (currMark == RewriteEvent.REPLACED) {
						ASTNode node= (ASTNode) currEvent.getOriginalValue();
						int currEnd= getEndOfNode(node);
						
						GroupDescription description= getDescription(currEvent);
						ASTNode changed= (ASTNode) currEvent.getNewValue();
						doTextRemoveAndVisit(currPos, currEnd - currPos, node, getDescription(currEvent));
						doTextInsert(currPos, changed, getNodeIndent(i), true, description);
						
						prevEnd= currEnd;
					} else { // is unchanged
						ASTNode node= (ASTNode) currEvent.getOriginalValue();
						doVisit(node);
					}
					if (i == lastNonInsert) { // last node or next nodes are all inserts
						separatorState= NONE;
						if (currMark == RewriteEvent.UNCHANGED) {
							ASTNode node= (ASTNode) currEvent.getOriginalValue();
							prevEnd= getEndOfNode(node);
						}
						currPos= prevEnd;
					} else if (fList[nextIndex].getChangeKind() != RewriteEvent.UNCHANGED) {
						// no updates needed while nodes are unchanged
						if (currMark == RewriteEvent.UNCHANGED) {
							ASTNode node= (ASTNode) currEvent.getOriginalValue();
							prevEnd= getEndOfNode(node);
						}
						currPos= getStartOfNextNode(nextIndex, prevEnd); // start of next
						separatorState= EXISTING;							
					}
				}

			}
			return currPos;
		}
	}
				
	private int rewriteRequiredNode(ASTNode parent, int property) {
		RewriteEvent event= getEvent(parent, property);
		if (event != null && event.getChangeKind() == RewriteEvent.REPLACED) {
			ASTNode node= (ASTNode) event.getOriginalValue();
			GroupDescription description= getDescription(event);
			int offset= getExtendedOffset(node);
			int length= getExtendedLength(node);
			doTextRemoveAndVisit(offset, length, node, description);
			doTextInsert(offset, (ASTNode) event.getNewValue(), getIndent(offset), true, description);
			return offset + length;			
		}
		return doVisit(parent, property, 0);
	}
		
	private int rewriteNode(ASTNode parent, int property, int offset, Prefix prefix) {
		RewriteEvent event= getEvent(parent, property);
		if (event != null) {
			switch (event.getChangeKind()) {
				case RewriteEvent.INSERTED: {
					ASTNode node= (ASTNode) event.getNewValue();
					GroupDescription description= getDescription(event);
					int indent= getIndent(offset);
					doTextInsert(offset, prefix.getPrefix(indent, getLineDelimiter()), description);
					doTextInsert(offset, node, indent, true, description);
					return offset;
				}
				case RewriteEvent.REMOVED: {	
					ASTNode node= (ASTNode) event.getOriginalValue();
					GroupDescription description= getDescription(event);
					
					int nodeOffset= getExtendedOffset(node);
					int nodeLen= getExtendedLength(node);		
					// if there is a prefix, remove the prefix as well
					int len= nodeOffset + nodeLen - offset;
					doTextRemoveAndVisit(offset, len, node, description);
					return nodeOffset + nodeLen;
				}
				case RewriteEvent.REPLACED: {
					ASTNode node= (ASTNode) event.getOriginalValue();
					GroupDescription description= getDescription(event);
					int nodeOffset= getExtendedOffset(node);
					int nodeLen= getExtendedLength(node);
					doTextRemoveAndVisit(nodeOffset, nodeLen, node, description);
					doTextInsert(nodeOffset, (ASTNode) event.getNewValue(), getIndent(offset), true, description);
					return nodeOffset + nodeLen;
				}
			}
		}
		return doVisit(parent, property, offset);
	}	
	
	
	/**
	 * endpos can be -1 -> 
	 */
	private int rewriteBodyNode(ASTNode parent, int property, int offset, int endPos, int indent, BlockContext context) {
		RewriteEvent event= getEvent(parent, property);
		if (event != null) {
			switch (event.getChangeKind()) {
				case RewriteEvent.INSERTED: {
					ASTNode node= (ASTNode) event.getNewValue();
					GroupDescription description= getDescription(event);
					
					String[] strings= context.getPrefixAndSuffix(indent, getLineDelimiter(), node);
					
					doTextInsert(offset, strings[0], description);
					doTextInsert(offset, node, indent, true, description);
					doTextInsert(offset, strings[1], description);
					return offset;
				}
				case RewriteEvent.REMOVED: {
					ASTNode node= (ASTNode) event.getOriginalValue();
					if (endPos == -1) {
						endPos= getExtendedEnd(node);
					}
					
					GroupDescription description= getDescription(event);
					// if there is a prefix, remove the prefix as well
					int len= endPos - offset;
					doTextRemoveAndVisit(offset, len, node, description);
					return endPos;
				}
				case RewriteEvent.REPLACED: {
					ASTNode node= (ASTNode) event.getOriginalValue();
					if (endPos == -1) {
						endPos= getExtendedEnd(node);
					}
					GroupDescription description= getDescription(event);
					int nodeLen= endPos - offset; 
					
					ASTNode replacingNode= (ASTNode) event.getNewValue();
					String[] strings= context.getPrefixAndSuffix(indent, getLineDelimiter(), replacingNode);
					doTextRemoveAndVisit(offset, nodeLen, node, description);
					
					String prefix= strings[0];
					doTextInsert(offset, prefix, description);
					String lineInPrefix= getCurrentLine(prefix, prefix.length());
					if (prefix.length() != lineInPrefix.length()) {
						// prefix contains a new line: update the indent to the one used in the prefix
						indent= Strings.computeIndent(lineInPrefix, CodeFormatterUtil.getTabWidth());
					}
					doTextInsert(offset, replacingNode, indent, true, description);
					doTextInsert(offset, strings[1], description);
					return endPos;
				}
			}
		}
		return doVisit(parent, property, offset);
	}
	
	private int rewriteOptionalQualifier(ASTNode parent, int property, int startPos) {
		RewriteEvent event= getEvent(parent, property);
		if (event != null) {
			switch (event.getChangeKind()) {
				case RewriteEvent.INSERTED: {
					ASTNode node= (ASTNode) event.getNewValue();
					GroupDescription description= getDescription(event);
					doTextInsert(startPos, node, getIndent(startPos), true, description);
					doTextInsert(startPos, ".", description); //$NON-NLS-1$
					return startPos;
				}
				case RewriteEvent.REMOVED: {
					try {
						ASTNode node= (ASTNode) event.getOriginalValue();
						GroupDescription description= getDescription(event);
						int dotEnd= getScanner().getTokenEndOffset(ITerminalSymbols.TokenNameDOT, node.getStartPosition() + node.getLength());
						doTextRemoveAndVisit(startPos, dotEnd - startPos, node, description);
						return dotEnd;
					} catch (CoreException e) {
						JavaPlugin.log(e);
					}
					break;
				}
				case RewriteEvent.REPLACED: {
					ASTNode node= (ASTNode) event.getOriginalValue();
					GroupDescription description= getDescription(event);
					int offset= getExtendedOffset(node);
					int length= getExtendedLength(node);
					
					doTextRemoveAndVisit(offset, length, node, description);
					doTextInsert(offset, (ASTNode) event.getNewValue(), getIndent(startPos), true, description);
					return offset + length;
				}
			}
		}
		return doVisit(parent, property, startPos);
	}	
	
	private class ParagraphListRewriter extends ListRewriter {
		
		public final int DEFAULT_SPACING= 1;
		
		private int fInitialIndent;
		private int fSeparatorLines;
		
		public ParagraphListRewriter(int initialIndent, int separator) {
			fInitialIndent= initialIndent;
			fSeparatorLines= separator;
		}
		
		protected int getInitialIndent() {
			return fInitialIndent;
		}
						
		protected String getSeparatorString(int nodeIndex) {
			int newLines= fSeparatorLines == -1 ? getNewLines(nodeIndex) : fSeparatorLines;
			
			String lineDelim= getLineDelimiter();
			StringBuffer buf= new StringBuffer(lineDelim);
			for (int i= 0; i < newLines; i++) {
				buf.append(lineDelim);
			}
			buf.append(CodeFormatterUtil.createIndentString(getNodeIndent(nodeIndex + 1)));
			return buf.toString();
		}
		
		private ASTNode getNode(int nodeIndex) {
			ASTNode elem= (ASTNode) fList[nodeIndex].getOriginalValue();
			if (elem == null) {
				elem= (ASTNode) fList[nodeIndex].getNewValue();
			}
			return elem;
		}
		
		private int getNewLines(int nodeIndex) {
			ASTNode curr= getNode(nodeIndex);
			ASTNode next= getNode(nodeIndex + 1);
			
			int currKind= curr.getNodeType();
			int nextKind= next.getNodeType();

			ASTNode last= null;
			ASTNode secondLast= null;
			for (int i= 0; i < fList.length; i++) {
				ASTNode elem= (ASTNode) fList[i].getOriginalValue();
				if (elem != null) {
					if (last != null) {
						if (elem.getNodeType() == nextKind && last.getNodeType() == currKind) {
							return countEmptyLines(last);
						}
						secondLast= last;
					}
					last= elem;
				}
			}
			if (currKind == ASTNode.FIELD_DECLARATION && nextKind == ASTNode.FIELD_DECLARATION ) {
				return 0;
			}
			if (secondLast != null) {
				return countEmptyLines(secondLast);
			}
			return DEFAULT_SPACING;
		}

		private int countEmptyLines(ASTNode last) {
			TextBuffer textBuffer= fTextBuffer;
			int lastLine= textBuffer.getLineOfOffset(last.getStartPosition() + last.getLength());
			int scanLine= lastLine + 1;
			int numLines= textBuffer.getNumberOfLines();
			while(scanLine < numLines && Strings.containsOnlyWhitespaces(textBuffer.getLineContent(scanLine))){
				scanLine++;
			}
			return scanLine - lastLine - 1;	
		}
		
	}
		
	private int rewriteParagraphList(ASTNode parent, int property, int insertPos, int insertIndent, int separator, int lead) {
		RewriteEvent event= getEvent(parent, property);
		if (event == null || event.getChangeKind() == ASTRewrite.UNCHANGED) {
			return doVisit(parent, property, insertPos);
		}
		
		RewriteEvent[] events= event.getChildren();
		boolean hasExisting= false;
		for (int i= 0; i < events.length; i++) {
			int changeKind= events[i].getChangeKind();
			hasExisting |= (changeKind != ASTRewrite.INSERTED);
		}
		
		ParagraphListRewriter listRewriter= new ParagraphListRewriter(insertIndent, separator);
		StringBuffer leadString= new StringBuffer();
		if (!hasExisting) {
			for (int i= 0; i < lead; i++) {
				leadString.append(getLineDelimiter());
			}
			leadString.append(CodeFormatterUtil.createIndentString(insertIndent));
		}
		return listRewriter.rewriteList(event.getChildren(), insertPos, leadString.toString());
	}
	
	private int rewriteNodeList(ASTNode parent, int property, int pos, String keyword, String separator) {
		RewriteEvent event= getEvent(parent, property);
		if (event != null && event.getChangeKind() != RewriteEvent.UNCHANGED) {
			return new ListRewriter().rewriteList(event.getChildren(), pos, keyword, separator);
		}
		return doVisit(parent, property, pos);
	}
	
	private void rewriteMethodBody(MethodDeclaration parent, int startPos) { 
		RewriteEvent event= getEvent(parent, ASTNodeConstants.BODY);
		if (event != null) {
			switch (event.getChangeKind()) {
				case RewriteEvent.INSERTED: {
					int endPos= parent.getStartPosition() + parent.getLength();
					GroupDescription description= getDescription(event);
					ASTNode body= (ASTNode) event.getNewValue();
					doTextRemove(startPos, endPos - startPos, description);
					int indent= getIndent(parent.getStartPosition());
					String prefix= ASTRewriteFormatter.METHOD_BODY.getPrefix(indent, getLineDelimiter());
					doTextInsert(startPos, prefix, description); 
					doTextInsert(startPos, body, indent, true, description);
					return;
				}
				case RewriteEvent.REMOVED: {
					GroupDescription description= getDescription(event);
					ASTNode body= (ASTNode) event.getOriginalValue();
					int endPos= parent.getStartPosition() + parent.getLength();
					doTextRemoveAndVisit(startPos, endPos - startPos, body, description);
					doTextInsert(startPos, ";", description); //$NON-NLS-1$
					return;
				}
				case RewriteEvent.REPLACED: {
					GroupDescription description= getDescription(event);
					ASTNode body= (ASTNode) event.getOriginalValue();
					doTextRemoveAndVisit(body.getStartPosition(), body.getLength(), body, description);
					doTextInsert(body.getStartPosition(), (ASTNode) event.getNewValue(), getIndent(body.getStartPosition()), true, description);
					return;
				}
			}
		}
		doVisit(parent, ASTNodeConstants.BODY, startPos);
	}
	
	private int rewriteExtraDimensions(ASTNode parent, int property, int pos) {
		RewriteEvent event= getEvent(parent, property);
		if (event == null || event.getChangeKind() == RewriteEvent.UNCHANGED) {
			return ((Integer) getOriginalValue(parent, property)).intValue();
		}
		int oldDim= ((Integer) event.getOriginalValue()).intValue();
		int newDim= ((Integer) event.getNewValue()).intValue();
		
		if (oldDim != newDim) {
			GroupDescription description= getDescription(event);
			rewriteExtraDimensions(oldDim, newDim, pos, description);
		}
		return oldDim;
	}
	
	private void rewriteExtraDimensions(int oldDim, int newDim, int pos, GroupDescription description) {
			
		if (oldDim < newDim) {
			for (int i= oldDim; i < newDim; i++) {
				doTextInsert(pos, "[]", description); //$NON-NLS-1$
			}
		} else if (newDim < oldDim) {
			try {
				getScanner().setOffset(pos);
				for (int i= newDim; i < oldDim; i++) {
					getScanner().readToToken(ITerminalSymbols.TokenNameRBRACKET);
				}
				doTextRemove(pos, getScanner().getCurrentEndOffset() - pos, description);
			} catch (CoreException e) {
				JavaPlugin.log(e);
			}
		}
	}	
		
	/*
	 * Next token is a left brace. Returns the offset after the brace. For incomplete code, return the start offset.  
	 */
	private int getPosAfterLeftBrace(int pos) {
		try {
			int nextToken= getScanner().readNext(pos, true);
			if (nextToken == ITerminalSymbols.TokenNameLBRACE) {
				return getScanner().getCurrentEndOffset();
			}
		} catch (CoreException e) {
			JavaPlugin.log(e);
		}
		return pos;
	}
	
	final int getIndent(int pos) {
		int line= fTextBuffer.getLineOfOffset(pos);
		return fTextBuffer.getLineIndent(line, CodeFormatterUtil.getTabWidth());
	}

	final void doTextInsert(int insertOffset, ASTNode node, int initialIndentLevel, boolean removeLeadingIndent, GroupDescription description) {		
		ArrayList markers= new ArrayList();
		String formatted= fFormatter.getFormattedResult(node, initialIndentLevel, markers);

		int tabWidth= CodeFormatterUtil.getTabWidth();
		int currPos= 0;
		if (removeLeadingIndent) {
			while (currPos < formatted.length() && Character.isWhitespace(formatted.charAt(currPos))) {
				currPos++;
			}
		}
		for (int i= 0; i < markers.size(); i++) { // markers.size can change!
			NodeMarker curr= (NodeMarker) markers.get(i);
			
			int offset= curr.offset;
			if (offset != currPos) {
				String insertStr= formatted.substring(currPos, offset); 
				doTextInsert(insertOffset, insertStr, description); // insert until the marker's begin
			}

			Object data= curr.data;
			if (data instanceof GroupDescription) { // tracking a node
				// need to split and create 2 edits as tracking node can surround replaced node.
				TextEdit edit= new RangeMarker(insertOffset, 0);
				addDescription((GroupDescription) data, edit);
				addEdit(edit);
				if (curr.length != 0) {
					int end= offset + curr.length;
					int k= i + 1;
					while (k < markers.size() && ((NodeMarker) markers.get(k)).offset < end) {
						k++;
					}
					curr.offset= end;
					curr.length= 0;
					markers.add(k, curr); // add again for end position
				}
				currPos= offset;
			} else {
				String destIndentString=  Strings.getIndentString(getCurrentLine(formatted, offset), tabWidth);
				if (data instanceof MovePlaceholderData) { // replace with a move target
					ASTNode existingNode= ((MovePlaceholderData) data).node;
					int srcIndentLevel= getIndent(existingNode.getStartPosition());
					doTextMove(existingNode, insertOffset, srcIndentLevel, destIndentString, tabWidth, description);
				} else if (data instanceof CopyPlaceholderData) { // replace with a copy target
					ASTNode existingNode= ((CopyPlaceholderData) data).node;
					int srcIndentLevel= getIndent(existingNode.getStartPosition());
					doTextCopy(existingNode, insertOffset, srcIndentLevel, destIndentString, tabWidth, description);
				} else if (data instanceof StringPlaceholderData) { // replace with a placeholder
					String code= ((StringPlaceholderData) data).code;
					String str= Strings.changeIndent(code, 0, tabWidth, destIndentString, getLineDelimiter()); 
					doTextInsert(insertOffset, str, description);
				}
				currPos= offset + curr.length; // continue to insert after the replaced string
			}

		}
		if (currPos < formatted.length()) {
			String insertStr= formatted.substring(currPos);
			doTextInsert(insertOffset, insertStr, description);
		}
	}
	
	private String getCurrentLine(String str, int pos) {
		for (int i= pos - 1; i>= 0; i--) {
			char ch= str.charAt(i);
			if (Strings.isLineDelimiterChar(ch)) {
				return str.substring(i + 1, pos);
			}
		}
		return str.substring(0, pos);
	}	
	

	private void rewriteModifiers(ASTNode parent, int property, int offset) {
		
		RewriteEvent event= getEvent(parent, property);
		if (event == null || event.getChangeKind() != RewriteEvent.REPLACED) {
			return;
		}
		
		int oldModifiers= ((Integer) event.getOriginalValue()).intValue();
		int newModifiers= ((Integer) event.getNewValue()).intValue();
		GroupDescription description= getDescription(event);
		
		try {
			int tok= getScanner().readNext(offset, true);
			int startPos= getScanner().getCurrentStartOffset();
			int endPos= startPos;
			loop: while (true) {
				boolean keep= true;
				switch (tok) {
					case ITerminalSymbols.TokenNamepublic: keep= Modifier.isPublic(newModifiers); break;
					case ITerminalSymbols.TokenNameprotected: keep= Modifier.isProtected(newModifiers); break;
					case ITerminalSymbols.TokenNameprivate: keep= Modifier.isPrivate(newModifiers); break;
					case ITerminalSymbols.TokenNamestatic: keep= Modifier.isStatic(newModifiers); break;
					case ITerminalSymbols.TokenNamefinal: keep= Modifier.isFinal(newModifiers); break;
					case ITerminalSymbols.TokenNameabstract: keep= Modifier.isAbstract(newModifiers); break;
					case ITerminalSymbols.TokenNamenative: keep= Modifier.isNative(newModifiers); break;
					case ITerminalSymbols.TokenNamevolatile: keep= Modifier.isVolatile(newModifiers); break;
					case ITerminalSymbols.TokenNamestrictfp: keep= Modifier.isStrictfp(newModifiers); break;
					case ITerminalSymbols.TokenNametransient: keep= Modifier.isTransient(newModifiers); break;
					case ITerminalSymbols.TokenNamesynchronized: keep= Modifier.isSynchronized(newModifiers); break;
					default:
						break loop;
				}
				tok= getScanner().readNext(true);
				int currPos= endPos;
				endPos= getScanner().getCurrentStartOffset();
				if (!keep) {
					doTextRemove(currPos, endPos - currPos, description);
				}
			} 
			int addedModifiers= newModifiers & ~oldModifiers;
			if (addedModifiers != 0) {
				if (startPos != endPos) {
					int visibilityModifiers= addedModifiers & (Modifier.PUBLIC | Modifier.PRIVATE | Modifier.PROTECTED);
					if (visibilityModifiers != 0) {
						StringBuffer buf= new StringBuffer();
						ASTFlattener.printModifiers(visibilityModifiers, buf);
						doTextInsert(startPos, buf.toString(), description);
						addedModifiers &= ~visibilityModifiers;
					}
				}
				StringBuffer buf= new StringBuffer();
				ASTFlattener.printModifiers(addedModifiers, buf);
				doTextInsert(endPos, buf.toString(), description);
			}
		} catch (CoreException e) {
			// ignore
		}		
	}
	
	private void replaceOperation(int posBeforeOperation, String newOperation, GroupDescription description) {
		try {
			getScanner().readNext(posBeforeOperation, true);
			doTextReplace(getScanner().getCurrentStartOffset(), getScanner().getCurrentLength(), newOperation, description);
		} catch (CoreException e) {
			JavaPlugin.log(e);
		}
	}
	
	private void rewriteOperation(ASTNode parent, int property, int posBeforeOperation) {
		RewriteEvent event= getEvent(parent, property);
		if (event != null && event.getChangeKind() != RewriteEvent.UNCHANGED) {
			try {
				String newOperation= event.getNewValue().toString();
				GroupDescription description= getDescription(event);
				getScanner().readNext(posBeforeOperation, true);
				doTextReplace(getScanner().getCurrentStartOffset(), getScanner().getCurrentLength(), newOperation, description);
			} catch (CoreException e) {
				JavaPlugin.log(e);
			}
		}
	}
	
	/* (non-Javadoc)
	 * @see org.eclipse.jdt.core.dom.ASTVisitor#postVisit(ASTNode)
	 */
	public void postVisit(ASTNode node) {
		Object annotation= fRewrite.getTrackedNodeData(node);
		if (annotation != null) {
			fCurrentEdit= fCurrentEdit.getParent();
		}
		int count= fRewrite.getCopyCount(node);
		while (count > 0) {
			fCurrentEdit= fCurrentEdit.getParent();
			count--;
		}
		if (isMoveSource(node)) {
			fCurrentEdit= fCurrentEdit.getParent();
		}
	}

	/* (non-Javadoc)
	 * @see org.eclipse.jdt.core.dom.ASTVisitor#preVisit(ASTNode)
	 */
	public void preVisit(ASTNode node) {
		// first move, then copies, then range marker
		TextEdit moveEdit= getMoveSource(node);
		if (moveEdit != null) {
			addEdit(moveEdit);
			fCurrentEdit= moveEdit;
		}
		TextEdit[] edits= getCopySources(node);
		if (edits != null) {
			for (int i= 0; i < edits.length; i++) {
				TextEdit edit= edits[i];
				addEdit(edit);
				fCurrentEdit= edit;		
			}
		}
		GroupDescription data= fRewrite.getTrackedNodeData(node);
		if (data != null) {
			int offset= getExtendedOffset(node);
			int length= getExtendedLength(node);
			TextEdit edit= new RangeMarker(offset, length);
			addDescription(data, edit);
			addEdit(edit);
			fCurrentEdit= edit;
		}
	}	

	/* (non-Javadoc)
	 * @see org.eclipse.jdt.core.dom.ASTVisitor#visit(CompilationUnit)
	 */ 
	public boolean visit(CompilationUnit node) {
		if (!hasChildrenChanges(node)) {
			return visitChildrenNeeded(node);
		}
		
		int startPos= rewriteNode(node, ASTNodeConstants.PACKAGE, 0, ASTRewriteFormatter.NONE); //$NON-NLS-1$
			
		if (getChangeKind(node, ASTNodeConstants.PACKAGE) == RewriteEvent.INSERTED) {
			doTextInsert(0, getLineDelimiter(), getDescription(node, ASTNodeConstants.PACKAGE));
		}
				
		startPos= rewriteParagraphList(node, ASTNodeConstants.IMPORTS, startPos, 0, 0, 2);
		rewriteParagraphList(node, ASTNodeConstants.TYPES, startPos, 0, -1, 2);
		return false;
	}


	/* (non-Javadoc)
	 * @see org.eclipse.jdt.core.dom.ASTVisitor#visit(TypeDeclaration)
	 */
	public boolean visit(TypeDeclaration node) {
		if (!hasChildrenChanges(node)) {
			return visitChildrenNeeded(node);
		}
		
		rewriteModifiers(node, ASTNodeConstants.MODIFIERS, node.getStartPosition());
		
		boolean isInterface= ((Boolean) getOriginalValue(node, ASTNodeConstants.IS_INTERFACE)).booleanValue();
		// modifiers & class/interface
		boolean invertType= isChanged(node, ASTNodeConstants.IS_INTERFACE);
		if (invertType) {
			try {
				int typeToken= isInterface ? ITerminalSymbols.TokenNameinterface : ITerminalSymbols.TokenNameclass;
				getScanner().readToToken(typeToken, node.getStartPosition());
				
				String str= isInterface ? "class" : "interface"; //$NON-NLS-1$ //$NON-NLS-2$
				int start= getScanner().getCurrentStartOffset();
				int end= getScanner().getCurrentEndOffset();
				
				doTextReplace(start, end - start, str, getDescription(node, ASTNodeConstants.IS_INTERFACE));
			} catch (CoreException e) {
				// ignore
			}			
		}
		
		// name
		int pos= rewriteRequiredNode(node, ASTNodeConstants.NAME);
		
		// superclass
		if (!isInterface || invertType) {
			RewriteEvent superClassEvent= getEvent(node, ASTNodeConstants.SUPERCLASS);
			int changeKind= superClassEvent != null ? superClassEvent.getChangeKind() : RewriteEvent.UNCHANGED;
			switch (changeKind) {
				case RewriteEvent.INSERTED: {
					ASTNode superClass= (ASTNode) superClassEvent.getNewValue();
					String str= " extends " + ASTNodes.asString(superClass); //$NON-NLS-1$
					doTextInsert(pos, str, getDescription(superClassEvent));
					break;
				}
				case RewriteEvent.REMOVED: {
					ASTNode superClass= (ASTNode) superClassEvent.getOriginalValue();
					int endPos= getExtendedEnd(superClass);
					doTextRemoveAndVisit(pos, endPos - pos, superClass, getDescription(superClassEvent));
					pos= endPos;
					break;
				}
				case RewriteEvent.REPLACED: {
					ASTNode superClass= (ASTNode) superClassEvent.getOriginalValue();
					int offset= getExtendedOffset(superClass);
					int length= getExtendedLength(superClass);
					doTextRemoveAndVisit(offset, length, superClass, getDescription(superClassEvent));
					doTextInsert(offset, (ASTNode) superClassEvent.getNewValue(), 0, false, getDescription(superClassEvent));
					pos= offset + length;
					break;
				}
				case RewriteEvent.UNCHANGED: {
					pos= doVisit(node, ASTNodeConstants.SUPERCLASS, pos);
				}
			}
		}
		// extended interfaces
		RewriteEvent interfaceEvent= getEvent(node, ASTNodeConstants.SUPER_INTERFACES);
		if (interfaceEvent == null || interfaceEvent.getChangeKind() == RewriteEvent.UNCHANGED) {
			if (invertType) {
				List originalNodes= (List) getOriginalValue(node, ASTNodeConstants.SUPER_INTERFACES);
				if (!originalNodes.isEmpty()) {
					String keyword= isInterface ? " implements " : " extends "; //$NON-NLS-1$ //$NON-NLS-2$
					ASTNode firstNode= (ASTNode) originalNodes.get(0);
					doTextReplace(pos, firstNode.getStartPosition() - pos, keyword, getDescription(node, ASTNodeConstants.IS_INTERFACE));
				}
			}
			pos= doVisit(node, ASTNodeConstants.SUPER_INTERFACES, pos);
		} else {
			String keyword= (isInterface == invertType) ? " implements " : " extends "; //$NON-NLS-1$ //$NON-NLS-2$
			if (invertType) {
				List newNodes= (List) getNewValue(node, ASTNodeConstants.SUPER_INTERFACES);
				if (!newNodes.isEmpty()) {
					List origNodes= (List) getOriginalValue(node, ASTNodeConstants.SUPER_INTERFACES);
					int firstStart= pos;
					if (!origNodes.isEmpty()) {
						firstStart= ((ASTNode) origNodes.get(0)).getStartPosition();
					}
					doTextReplace(pos, firstStart - pos, keyword, getDescription(node, ASTNodeConstants.IS_INTERFACE));
					keyword= ""; //$NON-NLS-1$
					pos= firstStart;
				}
			}
			pos= rewriteNodeList(node, ASTNodeConstants.SUPER_INTERFACES, pos, keyword, ", "); //$NON-NLS-1$
		}
		
		// type members
		// startPos : find position after left brace of type, be aware that bracket might be missing
		int startIndent= getIndent(node.getStartPosition()) + 1;
		int startPos= getPosAfterLeftBrace(pos);
		rewriteParagraphList(node, ASTNodeConstants.BODY_DECLARATIONS, startPos, startIndent, -1, 2);
		return false;
	}


	/* (non-Javadoc)
	 * @see org.eclipse.jdt.core.dom.ASTVisitor#visit(MethodDeclaration)
	 */
	public boolean visit(MethodDeclaration node) {
		if (!hasChildrenChanges(node)) {
			return visitChildrenNeeded(node);
		}
		
		rewriteModifiers(node, ASTNodeConstants.MODIFIERS, node.getStartPosition());
		
		boolean willBeConstructor= ((Boolean) getNewValue(node, ASTNodeConstants.IS_CONSTRUCTOR)).booleanValue();
	
		// return type
		int returnChangeKind= getChangeKind(node, ASTNodeConstants.RETURN_TYPE);
		if (!willBeConstructor || returnChangeKind == RewriteEvent.REMOVED) {
			try {
				int startPos= node.getStartPosition();
				if (returnChangeKind == RewriteEvent.REMOVED || returnChangeKind == RewriteEvent.INSERTED) {
					getScanner().setOffset(startPos);
					int token= getScanner().readNext(true);
					while (TokenScanner.isModifier(token)) {
						startPos= getScanner().getCurrentEndOffset();
						token= getScanner().readNext(true);
					}
				}
				ASTNode nameNode= (ASTNode) getOriginalValue(node, ASTNodeConstants.NAME);
				if (startPos == nameNode.getStartPosition()) {
					rewriteNode(node, ASTNodeConstants.RETURN_TYPE, startPos, ASTRewriteFormatter.NONE); //$NON-NLS-1$
					if (returnChangeKind == RewriteEvent.INSERTED) {
						doTextInsert(startPos, " ", getDescription(node, ASTNodeConstants.RETURN_TYPE)); //$NON-NLS-1$
					}
				} else {
					rewriteNode(node, ASTNodeConstants.RETURN_TYPE, startPos, ASTRewriteFormatter.SPACE); //$NON-NLS-1$
				}
			} catch (CoreException e) {
				JavaPlugin.log(e);
			}
		}
		// method name
		int pos= rewriteRequiredNode(node, ASTNodeConstants.NAME);
		
		// parameters
		try {
			if (isChanged(node, ASTNodeConstants.PARAMETERS)) {
				pos= getScanner().getTokenEndOffset(ITerminalSymbols.TokenNameLPAREN, pos);
				pos= rewriteNodeList(node, ASTNodeConstants.PARAMETERS, pos, "", ", "); //$NON-NLS-1$ //$NON-NLS-2$
			} else {
				pos= doVisit(node, ASTNodeConstants.PARAMETERS, pos);
			}
			
			pos= getScanner().getTokenEndOffset(ITerminalSymbols.TokenNameRPAREN, pos);
			
			int extraDims= rewriteExtraDimensions(node, ASTNodeConstants.EXTRA_DIMENSIONS, pos);
			
			boolean hasExceptionChanges= isChanged(node, ASTNodeConstants.THROWN_EXCEPTIONS);
			
			int bodyChangeKind= getChangeKind(node, ASTNodeConstants.BODY);
			
			if ((extraDims > 0) && (hasExceptionChanges || bodyChangeKind == RewriteEvent.INSERTED || bodyChangeKind == RewriteEvent.REMOVED)) {
				int dim= ((Integer) getOriginalValue(node, ASTNodeConstants.EXTRA_DIMENSIONS)).intValue();
				while (dim > 0) {
					pos= getScanner().getTokenEndOffset(ITerminalSymbols.TokenNameRBRACKET, pos);
					dim--;
				}
			}
			
			pos= rewriteNodeList(node, ASTNodeConstants.THROWN_EXCEPTIONS, pos, " throws ", ", "); //$NON-NLS-1$ //$NON-NLS-2$
			rewriteMethodBody(node, pos);
		} catch (CoreException e) {
			// ignore
		}
		return false;
	}
	
	/* (non-Javadoc)
	 * @see org.eclipse.jdt.core.dom.ASTVisitor#visit(Block)
	 */
	public boolean visit(Block node) {
		if (!hasChildrenChanges(node)) {
			return visitChildrenNeeded(node);
		}
		
		List collapsedChildren= fRewrite.getCollapsedNodes(node);
		int startPos;
		if (collapsedChildren != null) {
			startPos= node.getStartPosition();
		} else {
			startPos= getPosAfterLeftBrace(node.getStartPosition());
		}
		int startIndent= getIndent(node.getStartPosition()) + 1;
		rewriteParagraphList(node, ASTNodeConstants.STATEMENTS, startPos, startIndent, 0, 1);
		return false;
	}
	
	/* (non-Javadoc)
	 * @see org.eclipse.jdt.core.dom.ASTVisitor#visit(ReturnStatement)
	 */
	public boolean visit(ReturnStatement node) {
		if (!hasChildrenChanges(node)) {
			return visitChildrenNeeded(node);
		}
		
		try {
			int offset= getScanner().getTokenEndOffset(ITerminalSymbols.TokenNamereturn, node.getStartPosition());
			rewriteNode(node, ASTNodeConstants.EXPRESSION, offset, ASTRewriteFormatter.SPACE); //$NON-NLS-1$
		} catch (CoreException e) {
			JavaPlugin.log(e);
		}
		return false;
	}		
	

	/* (non-Javadoc)
	 * @see org.eclipse.jdt.core.dom.ASTVisitor#visit(AnonymousClassDeclaration)
	 */
	public boolean visit(AnonymousClassDeclaration node) {
		if (!hasChildrenChanges(node)) {
			return visitChildrenNeeded(node);
		}
		
		int startPos= getPosAfterLeftBrace(node.getStartPosition());
		int startIndent= getIndent(node.getStartPosition()) + 1;
		rewriteParagraphList(node, ASTNodeConstants.BODY_DECLARATIONS, startPos, startIndent, -1, 2);
		return false;
	}

	/* (non-Javadoc)
	 * @see org.eclipse.jdt.core.dom.ASTVisitor#visit(ArrayAccess)
	 */
	public boolean visit(ArrayAccess node) {
		if (!hasChildrenChanges(node)) {
			return visitChildrenNeeded(node);
		}
		
		rewriteRequiredNode(node, ASTNodeConstants.ARRAY);
		rewriteRequiredNode(node, ASTNodeConstants.INDEX);
		return false;
	}

	/* (non-Javadoc)
	 * @see org.eclipse.jdt.core.dom.ASTVisitor#visit(ArrayCreation)
	 */
	public boolean visit(ArrayCreation node) {
		if (!hasChildrenChanges(node)) {
			return visitChildrenNeeded(node);
		}
		
		ArrayType arrayType= (ArrayType) getOriginalValue(node, ASTNodeConstants.TYPE);
		int nOldBrackets= getDimensions(arrayType); // number of total brackets
		int nNewBrackets= nOldBrackets;
		
		GroupDescription description= null;
		RewriteEvent typeEvent= getEvent(node, ASTNodeConstants.TYPE);
		if (typeEvent != null && typeEvent.getChangeKind() == RewriteEvent.REPLACED) { // changed arraytype can have different dimension or type name
			ArrayType replacingType= (ArrayType) typeEvent.getNewValue();
			description= getDescription(typeEvent);
			Type newType= replacingType.getElementType();
			Type oldType= getElementType(arrayType);
			if (!newType.equals(oldType)) {
				int offset= getExtendedOffset(oldType);
				int length= getExtendedLength(oldType);
				doTextRemove(offset, length, description);
				doTextInsert(offset, newType, 0, false, description);
			}
			nNewBrackets= replacingType.getDimensions(); // is replaced type
		}
		doVisit(arrayType);
		

		try {
			int offset= getScanner().getTokenStartOffset(ITerminalSymbols.TokenNameLBRACKET, arrayType.getStartPosition());
			// dimension node with expressions
			RewriteEvent dimEvent= getEvent(node, ASTNodeConstants.DIMENSIONS);
			boolean hasDimensionChanges= (dimEvent != null && dimEvent.getChangeKind() != RewriteEvent.UNCHANGED);
			if (hasDimensionChanges) {
				RewriteEvent[] events= dimEvent.getChildren();
				// offset on first opening brace
				for (int i= 0; i < events.length; i++) {
					RewriteEvent event= events[i];
					int changeKind= event.getChangeKind();
					if (changeKind == RewriteEvent.INSERTED) { // insert new dimension
						description= getDescription(event);
						doTextInsert(offset, "[", description); //$NON-NLS-1$
						doTextInsert(offset, (ASTNode) event.getNewValue(), 0, false, description);
						doTextInsert(offset, "]", description); //$NON-NLS-1$
						nNewBrackets--;
					} else {
						ASTNode elem= (ASTNode) event.getOriginalValue();
						int elemEnd= elem.getStartPosition() + elem.getLength();
						int endPos= getScanner().getTokenEndOffset(ITerminalSymbols.TokenNameRBRACKET, elemEnd);
						if (changeKind == RewriteEvent.REMOVED) {
							description= getDescription(event);
							doTextRemoveAndVisit(offset, endPos - offset, elem, description);
						} else if (changeKind == RewriteEvent.REPLACED) {
							description= getDescription(event);
							int elemOffset= getExtendedOffset(elem);
							int elemLength= getExtendedLength(elem);
							doTextRemoveAndVisit(elemOffset, elemLength, elem, description);
							doTextInsert(elemOffset, (ASTNode) event.getNewValue(), 0, false, description);
							nNewBrackets--;
						} else {
							doVisit(elem);
							nNewBrackets--;
						}
						offset= endPos;
						nOldBrackets--;
					}
				}
			} else {
				offset= doVisit(node, ASTNodeConstants.DIMENSIONS, offset);
			}
			if (nOldBrackets != nNewBrackets) {
				if (!hasDimensionChanges) {
					offset= getScanner().getTokenEndOffset(ITerminalSymbols.TokenNameRBRACKET, offset);
				}
				rewriteExtraDimensions(nOldBrackets, nNewBrackets, offset, description);
			}
				
			int kind= getChangeKind(node, ASTNodeConstants.INITIALIZER);
			if (kind == RewriteEvent.REMOVED) {
				offset= getScanner().getPreviousTokenEndOffset(ITerminalSymbols.TokenNameLBRACE, offset);
			} else {
				offset= node.getStartPosition() + node.getLength(); // insert pos
			}
			rewriteNode(node, ASTNodeConstants.INITIALIZER, offset, ASTRewriteFormatter.SPACE); //$NON-NLS-1$
		} catch (CoreException e) {
			JavaPlugin.log(e);
		}		
		return false;
	}
	
	private Type getElementType(ArrayType parent) {
		Type t = (Type) getOriginalValue(parent, ASTNodeConstants.COMPONENT_TYPE);
		while (t.isArrayType()) {
			t = (Type) getOriginalValue(t, ASTNodeConstants.COMPONENT_TYPE);
		}
		return t;
	}
	
	private int getDimensions(ArrayType parent) {
		Type t = (Type) getOriginalValue(parent, ASTNodeConstants.COMPONENT_TYPE);
		int dimensions = 1; // always include this array type
		while (t.isArrayType()) {
			dimensions++;
			t = (Type) getOriginalValue(t, ASTNodeConstants.COMPONENT_TYPE);
		}
		return dimensions;
	}	

	/* (non-Javadoc)
	 * @see org.eclipse.jdt.core.dom.ASTVisitor#visit(ArrayInitializer)
	 */
	public boolean visit(ArrayInitializer node) {
		if (!hasChildrenChanges(node)) {
			return visitChildrenNeeded(node);
		}

		int startPos= getPosAfterLeftBrace(node.getStartPosition());
		rewriteNodeList(node, ASTNodeConstants.EXPRESSIONS, startPos, "", ", "); //$NON-NLS-1$ //$NON-NLS-2$
		return false;
	}



	/* (non-Javadoc)
	 * @see org.eclipse.jdt.core.dom.ASTVisitor#visit(ArrayType)
	 */
	public boolean visit(ArrayType node) {
		if (!hasChildrenChanges(node)) {
			return visitChildrenNeeded(node);
		}
		
		rewriteRequiredNode(node, ASTNodeConstants.COMPONENT_TYPE);
		return false;
	}

	/* (non-Javadoc)
	 * @see org.eclipse.jdt.core.dom.ASTVisitor#visit(AssertStatement)
	 */
	public boolean visit(AssertStatement node) {
		if (!hasChildrenChanges(node)) {
			return visitChildrenNeeded(node);
		}
		
		int offset= rewriteRequiredNode(node, ASTNodeConstants.EXPRESSION);
		rewriteNode(node, ASTNodeConstants.MESSAGE, offset, ASTRewriteFormatter.ASSERT_COMMENT);
		return false;
	}

	/* (non-Javadoc)
	 * @see org.eclipse.jdt.core.dom.ASTVisitor#visit(Assignment)
	 */
	public boolean visit(Assignment node) {
		if (!hasChildrenChanges(node)) {
			return visitChildrenNeeded(node);
		}
		
		int pos= rewriteRequiredNode(node, ASTNodeConstants.LEFT_HAND_SIDE);
		rewriteOperation(node, ASTNodeConstants.OPERATOR, pos);
		rewriteRequiredNode(node, ASTNodeConstants.RIGHT_HAND_SIDE);
		return false;
	}

	/* (non-Javadoc)
	 * @see org.eclipse.jdt.core.dom.ASTVisitor#visit(BooleanLiteral)
	 */
	public boolean visit(BooleanLiteral node) {
		if (!hasChildrenChanges(node)) {
			return visitChildrenNeeded(node);
		}
		
		changeNotSupported(node); // no modification possible
		return false;
	}

	/* (non-Javadoc)
	 * @see org.eclipse.jdt.core.dom.ASTVisitor#visit(BreakStatement)
	 */
	public boolean visit(BreakStatement node) {
		if (!hasChildrenChanges(node)) {
			return visitChildrenNeeded(node);
		}

		try {
			int offset= getScanner().getTokenEndOffset(ITerminalSymbols.TokenNamebreak, node.getStartPosition());
			rewriteNode(node, ASTNodeConstants.LABEL, offset, ASTRewriteFormatter.SPACE); // space between break and label //$NON-NLS-1$
		} catch (CoreException e) {
			JavaPlugin.log(e);
		}
		return false;		
	}

	/* (non-Javadoc)
	 * @see org.eclipse.jdt.core.dom.ASTVisitor#visit(CastExpression)
	 */
	public boolean visit(CastExpression node) {
		if (!hasChildrenChanges(node)) {
			return visitChildrenNeeded(node);
		}
		
		rewriteRequiredNode(node, ASTNodeConstants.TYPE);
		rewriteRequiredNode(node, ASTNodeConstants.EXPRESSION);
		return false;
	}

	/* (non-Javadoc)
	 * @see org.eclipse.jdt.core.dom.ASTVisitor#visit(CatchClause)
	 */
	public boolean visit(CatchClause node) { // catch (Exception) Block
		if (!hasChildrenChanges(node)) {
			return visitChildrenNeeded(node);
		}
		
		rewriteRequiredNode(node, ASTNodeConstants.EXCEPTION);
		rewriteRequiredNode(node, ASTNodeConstants.BODY);
		return false;
	}

	/* (non-Javadoc)
	 * @see org.eclipse.jdt.core.dom.ASTVisitor#visit(CharacterLiteral)
	 */
	public boolean visit(CharacterLiteral node) {
		if (!hasChildrenChanges(node)) {
			return visitChildrenNeeded(node);
		}
		
		changeNotSupported(node); // no modification possible
		return false;
	}

	/* (non-Javadoc)
	 * @see org.eclipse.jdt.core.dom.ASTVisitor#visit(ClassInstanceCreation)
	 */
	public boolean visit(ClassInstanceCreation node) {
		if (!hasChildrenChanges(node)) {
			return visitChildrenNeeded(node);
		}
		
		rewriteOptionalQualifier(node, ASTNodeConstants.EXPRESSION, node.getStartPosition());
		
		int pos= rewriteRequiredNode(node, ASTNodeConstants.NAME);

		if (isChanged(node, ASTNodeConstants.ARGUMENTS)) {
			try {
				int startpos= getScanner().getTokenEndOffset(ITerminalSymbols.TokenNameLPAREN, pos);
				rewriteNodeList(node, ASTNodeConstants.ARGUMENTS, startpos, "", ", "); //$NON-NLS-1$ //$NON-NLS-2$
			} catch (CoreException e) {
				JavaPlugin.log(e);
			}
		} else {
			doVisit(node, ASTNodeConstants.ARGUMENTS, 0);
		}
		
		int kind= getChangeKind(node, ASTNodeConstants.ANONYMOUS_CLASS_DECLARATION);
		if (kind == RewriteEvent.REMOVED) {
			try {
				pos= getScanner().getPreviousTokenEndOffset(ITerminalSymbols.TokenNameLBRACE, pos);
			} catch (CoreException e) {
				JavaPlugin.log(e);
			}
		} else {
			pos= node.getStartPosition() + node.getLength(); // insert pos
		}
		rewriteNode(node, ASTNodeConstants.ANONYMOUS_CLASS_DECLARATION, pos, ASTRewriteFormatter.SPACE); //$NON-NLS-1$
		return false;
	}

	/* (non-Javadoc)
	 * @see org.eclipse.jdt.core.dom.ASTVisitor#visit(ConditionalExpression)
	 */
	public boolean visit(ConditionalExpression node) { // expression ? thenExpression : elseExpression
		if (!hasChildrenChanges(node)) {
			return visitChildrenNeeded(node);
		}
		
		rewriteRequiredNode(node, ASTNodeConstants.EXPRESSION);
		rewriteRequiredNode(node, ASTNodeConstants.THEN_EXPRESSION);
		rewriteRequiredNode(node, ASTNodeConstants.ELSE_EXPRESSION);	
		return false;
	}

	/* (non-Javadoc)
	 * @see org.eclipse.jdt.core.dom.ASTVisitor#visit(ConstructorInvocation)
	 */
	public boolean visit(ConstructorInvocation node) {
		if (!hasChildrenChanges(node)) {
			return visitChildrenNeeded(node);
		}
		
		try {
			int startpos= getScanner().getTokenEndOffset(ITerminalSymbols.TokenNameLPAREN, node.getStartPosition());
			rewriteNodeList(node, ASTNodeConstants.ARGUMENTS, startpos, "", ", "); //$NON-NLS-1$ //$NON-NLS-2$
		} catch (CoreException e) {
			JavaPlugin.log(e);
		}
		return false;
	}

	/* (non-Javadoc)
	 * @see org.eclipse.jdt.core.dom.ASTVisitor#visit(ContinueStatement)
	 */
	public boolean visit(ContinueStatement node) {
		if (!hasChildrenChanges(node)) {
			return visitChildrenNeeded(node);
		}
		
		try {
			int offset= getScanner().getTokenEndOffset(ITerminalSymbols.TokenNamecontinue, node.getStartPosition());
			rewriteNode(node, ASTNodeConstants.LABEL, offset, ASTRewriteFormatter.SPACE); // space between continue and label //$NON-NLS-1$
		} catch (CoreException e) {
			JavaPlugin.log(e);
		}
		return false;
	}

	/* (non-Javadoc)
	 * @see org.eclipse.jdt.core.dom.ASTVisitor#visit(DoStatement)
	 */
	public boolean visit(DoStatement node) { // do statement while expression
		if (!hasChildrenChanges(node)) {
			return visitChildrenNeeded(node);
		}
		
		int pos= node.getStartPosition();
		try {
			RewriteEvent event= getEvent(node, ASTNodeConstants.BODY);
			if (event != null && event.getChangeKind() == RewriteEvent.REPLACED) {
				int startOffset= getScanner().getTokenEndOffset(ITerminalSymbols.TokenNamedo, pos);
				ASTNode body= (ASTNode) event.getOriginalValue();
				int bodyEnd= body.getStartPosition() + body.getLength();
				int endPos= getScanner().getTokenStartOffset(ITerminalSymbols.TokenNamewhile, bodyEnd);
				rewriteBodyNode(node, ASTNodeConstants.BODY, startOffset, endPos, getIndent(node.getStartPosition()), ASTRewriteFormatter.DO_BLOCK); // body
			} else {
				doVisit(node, ASTNodeConstants.BODY, pos);
			}
		} catch (CoreException e) {
			JavaPlugin.log(e);
		}

		rewriteRequiredNode(node, ASTNodeConstants.EXPRESSION);	
		return false;
	}

	/* (non-Javadoc)
	 * @see org.eclipse.jdt.core.dom.ASTVisitor#visit(EmptyStatement)
	 */
	public boolean visit(EmptyStatement node) {
		if (!hasChildrenChanges(node)) {
			return visitChildrenNeeded(node);
		}
		
		changeNotSupported(node); // no modification possible
		return false;
	}

	/* (non-Javadoc)
	 * @see org.eclipse.jdt.core.dom.ASTVisitor#visit(ExpressionStatement)
	 */
	public boolean visit(ExpressionStatement node) { // expression
		if (!hasChildrenChanges(node)) {
			return visitChildrenNeeded(node);
		}
		
		rewriteRequiredNode(node, ASTNodeConstants.EXPRESSION);	
		return false;
	}

	/* (non-Javadoc)
	 * @see org.eclipse.jdt.core.dom.ASTVisitor#visit(FieldAccess)
	 */
	public boolean visit(FieldAccess node) { // expression.name
		if (!hasChildrenChanges(node)) {
			return visitChildrenNeeded(node);
		}
		
		rewriteRequiredNode(node, ASTNodeConstants.EXPRESSION); // expression
		rewriteRequiredNode(node, ASTNodeConstants.NAME); // name
		return false;
	}

	/* (non-Javadoc)
	 * @see org.eclipse.jdt.core.dom.ASTVisitor#visit(FieldDeclaration)
	 */
	public boolean visit(FieldDeclaration node) { //{ Modifier } Type VariableDeclarationFragment { ',' VariableDeclarationFragment } ';'
		if (!hasChildrenChanges(node)) {
			return visitChildrenNeeded(node);
		}
		
		rewriteModifiers(node, ASTNodeConstants.MODIFIERS, node.getStartPosition());
		int pos= rewriteRequiredNode(node, ASTNodeConstants.TYPE);
		rewriteNodeList(node, ASTNodeConstants.FRAGMENTS, pos, "", ", "); //$NON-NLS-1$ //$NON-NLS-2$
		return false;
	}

	/* (non-Javadoc)
	 * @see org.eclipse.jdt.core.dom.ASTVisitor#visit(ForStatement)
	 */
	public boolean visit(ForStatement node) {
		if (!hasChildrenChanges(node)) {
			return visitChildrenNeeded(node);
		}
		
		try {
			int pos= node.getStartPosition();
			
			if (isChanged(node, ASTNodeConstants.INITIALIZERS)) {
				// position after opening parent
				int startOffset= getScanner().getTokenEndOffset(ITerminalSymbols.TokenNameLPAREN, pos);
				pos= rewriteNodeList(node, ASTNodeConstants.INITIALIZERS, startOffset, "", ", "); //$NON-NLS-1$ //$NON-NLS-2$
			} else {
				pos= doVisit(node, ASTNodeConstants.INITIALIZERS, pos);
			}
			
			// position after first semicolon
			pos= getScanner().getTokenEndOffset(ITerminalSymbols.TokenNameSEMICOLON, pos);
			
			pos= rewriteNode(node, ASTNodeConstants.EXPRESSION, pos, ASTRewriteFormatter.NONE);
			
			if (isChanged(node, ASTNodeConstants.UPDATERS)) {
				int startOffset= getScanner().getTokenEndOffset(ITerminalSymbols.TokenNameSEMICOLON, pos);
				pos= rewriteNodeList(node, ASTNodeConstants.UPDATERS, startOffset, "", ", "); //$NON-NLS-1$ //$NON-NLS-2$
			} else {
				pos= doVisit(node, ASTNodeConstants.UPDATERS, pos);
			}

			RewriteEvent bodyEvent= getEvent(node, ASTNodeConstants.BODY);
			if (bodyEvent != null && bodyEvent.getChangeKind() == RewriteEvent.REPLACED) {
				int startOffset= getScanner().getTokenEndOffset(ITerminalSymbols.TokenNameRPAREN, pos);
				rewriteBodyNode(node, ASTNodeConstants.BODY, startOffset, -1, getIndent(node.getStartPosition()), ASTRewriteFormatter.WHILE_BLOCK); // body
			} else {
				doVisit(node, ASTNodeConstants.BODY, 0);
			}
			
		} catch (CoreException e) {
			JavaPlugin.log(e);
		}
			
		
		return false;
	}

	/* (non-Javadoc)
	 * @see org.eclipse.jdt.core.dom.ASTVisitor#visit(IfStatement)
	 */
	public boolean visit(IfStatement node) {
		if (!hasChildrenChanges(node)) {
			return visitChildrenNeeded(node);
		}
		
		int pos= rewriteRequiredNode(node, ASTNodeConstants.EXPRESSION); // statement
		
		int thenChange= getChangeKind(node, ASTNodeConstants.THEN_STATEMENT);
		int elseChange= getChangeKind(node, ASTNodeConstants.ELSE_STATEMENT);

		if (thenChange != RewriteEvent.UNCHANGED) {
			try {
				pos= getScanner().getTokenEndOffset(ITerminalSymbols.TokenNameRPAREN, pos); // after the closing parent
				int indent= getIndent(node.getStartPosition());
				
				int endPos= -1;
				Object elseStatement= getOriginalValue(node, ASTNodeConstants.ELSE_STATEMENT);
				if (elseStatement != null) {
					endPos= getScanner().getTokenStartOffset(ITerminalSymbols.TokenNameelse, pos); // else keyword
				}
				if ((elseChange != RewriteEvent.UNCHANGED) || (elseStatement == null)) {
					pos= rewriteBodyNode(node, ASTNodeConstants.THEN_STATEMENT, pos, endPos, indent, ASTRewriteFormatter.IF_BLOCK_NO_ELSE); 
				} else {
					pos= rewriteBodyNode(node, ASTNodeConstants.THEN_STATEMENT, pos, endPos, indent, ASTRewriteFormatter.IF_BLOCK_WITH_ELSE); 
				}
			} catch (CoreException e) {
				JavaPlugin.log(e);
			}
		} else {
			pos= doVisit(node, ASTNodeConstants.THEN_STATEMENT, pos);
		}

		if (elseChange != RewriteEvent.UNCHANGED) {
			int indent= getIndent(node.getStartPosition());
			Object newThen= getNewValue(node, ASTNodeConstants.THEN_STATEMENT);
			if (newThen instanceof Block) {
				rewriteBodyNode(node, ASTNodeConstants.ELSE_STATEMENT, pos, -1, indent, ASTRewriteFormatter.ELSE_AFTER_BLOCK);
			} else {
				rewriteBodyNode(node, ASTNodeConstants.ELSE_STATEMENT, pos, -1, indent, ASTRewriteFormatter.ELSE_AFTER_STATEMENT);
			}
		} else {
			pos= doVisit(node, ASTNodeConstants.ELSE_STATEMENT, pos);
		}
		return false;
	}

	/* (non-Javadoc)
	 * @see org.eclipse.jdt.core.dom.ASTVisitor#visit(ImportDeclaration)
	 */
	public boolean visit(ImportDeclaration node) {
		if (!hasChildrenChanges(node)) {
			return visitChildrenNeeded(node);
		}
		
		int pos= rewriteRequiredNode(node, ASTNodeConstants.NAME);
		
		RewriteEvent event= getEvent(node, ASTNodeConstants.IS_ON_DEMAND);
		if (event != null && event.getChangeKind() != RewriteEvent.UNCHANGED) {
			boolean isOnDemand= ((Boolean) event.getOriginalValue()).booleanValue();
			if (!isOnDemand) {
				doTextInsert(pos, ".*", getDescription(event)); //$NON-NLS-1$
			} else {
				try {
					int endPos= getScanner().getTokenStartOffset(ITerminalSymbols.TokenNameSEMICOLON, pos);
					doTextRemove(pos, endPos - pos, getDescription(event));
				} catch (CoreException e) {
					JavaPlugin.log(e);
				}
			}
		}
		return false;
	}
	

	

	/* (non-Javadoc)
	 * @see org.eclipse.jdt.core.dom.ASTVisitor#visit(InfixExpression)
	 */
	public boolean visit(InfixExpression node) {
		if (!hasChildrenChanges(node)) {
			return visitChildrenNeeded(node);
		}
		
		int pos= rewriteRequiredNode(node, ASTNodeConstants.LEFT_OPERAND);
		
		boolean needsNewOperation= isChanged(node, ASTNodeConstants.OPERATOR);
		String operation= getNewValue(node, ASTNodeConstants.OPERATOR).toString();
		if (needsNewOperation) {
			replaceOperation(pos, operation, getDescription(node, ASTNodeConstants.OPERATOR));
		}
			
		pos= rewriteRequiredNode(node, ASTNodeConstants.RIGHT_OPERAND);
		
		RewriteEvent event= getEvent(node, ASTNodeConstants.EXTENDED_OPERANDS);
		String prefixString= ' ' + operation + ' ';
		
		if (needsNewOperation) {
			int startPos= pos;
			GroupDescription description= getDescription(node, ASTNodeConstants.OPERATOR);
			
			if (event != null && event.getChangeKind() != RewriteEvent.UNCHANGED) {
				RewriteEvent[] extendedOperands= event.getChildren();
				for (int i= 0; i < extendedOperands.length; i++) {
					RewriteEvent curr= extendedOperands[i];
					ASTNode elem= (ASTNode) curr.getOriginalValue();
					if (elem != null) {
						if (curr.getChangeKind() != RewriteEvent.REPLACED) {
							replaceOperation(startPos, operation, description);
						}
						startPos= elem.getStartPosition() + elem.getLength();
					}
				}
			} else {
				List extendedOperands= (List) getOriginalValue(node, ASTNodeConstants.EXTENDED_OPERANDS);
				for (int i= 0; i < extendedOperands.size(); i++) {
					ASTNode elem= (ASTNode) extendedOperands.get(i);
					replaceOperation(startPos, operation, description);
					startPos= elem.getStartPosition() + elem.getLength();
				}				
			}
		}
		rewriteNodeList(node, ASTNodeConstants.EXTENDED_OPERANDS, pos, prefixString, prefixString);
		return false;
	}

	/* (non-Javadoc)
	 * @see org.eclipse.jdt.core.dom.ASTVisitor#visit(Initializer)
	 */
	public boolean visit(Initializer node) {
		if (!hasChildrenChanges(node)) {
			return visitChildrenNeeded(node);
		}
		
		rewriteModifiers(node, ASTNodeConstants.MODIFIERS, node.getStartPosition());
		rewriteRequiredNode(node, ASTNodeConstants.BODY);
		return false;
	}

	/* (non-Javadoc)
	 * @see org.eclipse.jdt.core.dom.ASTVisitor#visit(InstanceofExpression)
	 */
	public boolean visit(InstanceofExpression node) {
		if (!hasChildrenChanges(node)) {
			return visitChildrenNeeded(node);
		}
		
		rewriteRequiredNode(node, ASTNodeConstants.LEFT_OPERAND);
		rewriteRequiredNode(node, ASTNodeConstants.RIGHT_OPERAND);
		return false;
	}

	/* (non-Javadoc)
	 * @see org.eclipse.jdt.core.dom.ASTVisitor#visit(Javadoc)
	 */
	public boolean visit(Javadoc node) {
		if (!hasChildrenChanges(node)) {
			return visitChildrenNeeded(node);
		}
		
		changeNotSupported(node); // no modification possible
		return false;
	}

	/* (non-Javadoc)
	 * @see org.eclipse.jdt.core.dom.ASTVisitor#visit(LabeledStatement)
	 */
	public boolean visit(LabeledStatement node) {
		if (!hasChildrenChanges(node)) {
			return visitChildrenNeeded(node);
		}
		
		rewriteRequiredNode(node, ASTNodeConstants.LABEL);
		rewriteRequiredNode(node, ASTNodeConstants.BODY);		
		return false;
	}

	/* (non-Javadoc)
	 * @see org.eclipse.jdt.core.dom.ASTVisitor#visit(MethodInvocation)
	 */
	public boolean visit(MethodInvocation node) {
		if (!hasChildrenChanges(node)) {
			return visitChildrenNeeded(node);
		}
		
		rewriteOptionalQualifier(node, ASTNodeConstants.EXPRESSION, node.getStartPosition());

		int pos= rewriteRequiredNode(node, ASTNodeConstants.NAME);
		
		
		if (isChanged(node, ASTNodeConstants.ARGUMENTS)) {
			// eval position after opening parent
			try {
				int startOffset= getScanner().getTokenEndOffset(ITerminalSymbols.TokenNameLPAREN, pos);
				rewriteNodeList(node, ASTNodeConstants.ARGUMENTS, startOffset, "", ", "); //$NON-NLS-1$ //$NON-NLS-2$
			} catch (CoreException e) {
				JavaPlugin.log(e);
			}
		} else {
			doVisit(node, ASTNodeConstants.ARGUMENTS, 0);
		}
		return false;
	}

	/* (non-Javadoc)
	 * @see org.eclipse.jdt.core.dom.ASTVisitor#visit(NullLiteral)
	 */
	public boolean visit(NullLiteral node) {
		if (!hasChildrenChanges(node)) {
			return visitChildrenNeeded(node);
		}
		
		changeNotSupported(node); // no modification possible
		return false;
	}

	/* (non-Javadoc)
	 * @see org.eclipse.jdt.core.dom.ASTVisitor#visit(NumberLiteral)
	 */
	public boolean visit(NumberLiteral node) {
		if (!hasChildrenChanges(node)) {
			return visitChildrenNeeded(node);
		}
		
		changeNotSupported(node); // no modification possible
		return false;
	}

	/* (non-Javadoc)
	 * @see org.eclipse.jdt.core.dom.ASTVisitor#visit(PackageDeclaration)
	 */
	public boolean visit(PackageDeclaration node) {
		if (!hasChildrenChanges(node)) {
			return visitChildrenNeeded(node);
		}		
		
		rewriteRequiredNode(node, ASTNodeConstants.NAME);
		return false;
	}

	/* (non-Javadoc)
	 * @see org.eclipse.jdt.core.dom.ASTVisitor#visit(ParenthesizedExpression)
	 */
	public boolean visit(ParenthesizedExpression node) {
		if (!hasChildrenChanges(node)) {
			return visitChildrenNeeded(node);
		}
		
		rewriteRequiredNode(node, ASTNodeConstants.EXPRESSION);
		return false;
	}

	/* (non-Javadoc)
	 * @see org.eclipse.jdt.core.dom.ASTVisitor#visit(PostfixExpression)
	 */
	public boolean visit(PostfixExpression node) {
		if (!hasChildrenChanges(node)) {
			return visitChildrenNeeded(node);
		}
		
		int pos= rewriteRequiredNode(node, ASTNodeConstants.OPERAND);
		rewriteOperation(node, ASTNodeConstants.OPERATOR, pos);
		return false;		
	}

	/* (non-Javadoc)
	 * @see org.eclipse.jdt.core.dom.ASTVisitor#visit(PrefixExpression)
	 */
	public boolean visit(PrefixExpression node) {
		if (!hasChildrenChanges(node)) {
			return visitChildrenNeeded(node);
		}
		
		rewriteOperation(node, ASTNodeConstants.OPERATOR, node.getStartPosition());
		rewriteRequiredNode(node, ASTNodeConstants.OPERAND);
		return false;	
	}

	/* (non-Javadoc)
	 * @see org.eclipse.jdt.core.dom.ASTVisitor#visit(PrimitiveType)
	 */
	public boolean visit(PrimitiveType node) {
		if (!hasChildrenChanges(node)) {
			return visitChildrenNeeded(node);
		}
		
		changeNotSupported(node); // no modification possible
		return false;
	}

	/* (non-Javadoc)
	 * @see org.eclipse.jdt.core.dom.ASTVisitor#visit(QualifiedName)
	 */
	public boolean visit(QualifiedName node) {
		if (!hasChildrenChanges(node)) {
			return visitChildrenNeeded(node);
		}
		
		rewriteRequiredNode(node, ASTNodeConstants.QUALIFIER);
		rewriteRequiredNode(node, ASTNodeConstants.NAME);
		return false;
	}

	/* (non-Javadoc)
	 * @see org.eclipse.jdt.core.dom.ASTVisitor#visit(SimpleName)
	 */
	public boolean visit(SimpleName node) {
		if (!hasChildrenChanges(node)) {
			return visitChildrenNeeded(node);
		}
		
		changeNotSupported(node); // no modification possible
		return false;
	}

	/* (non-Javadoc)
	 * @see org.eclipse.jdt.core.dom.ASTVisitor#visit(SimpleType)
	 */
	public boolean visit(SimpleType node) {
		if (!hasChildrenChanges(node)) {
			return visitChildrenNeeded(node);
		}
		
		rewriteRequiredNode(node, ASTNodeConstants.NAME);
		return false;
	}

	/* (non-Javadoc)
	 * @see org.eclipse.jdt.core.dom.ASTVisitor#visit(SingleVariableDeclaration)
	 */
	public boolean visit(SingleVariableDeclaration node) {
		if (!hasChildrenChanges(node)) {
			return visitChildrenNeeded(node);
		}
		
		rewriteModifiers(node, ASTNodeConstants.MODIFIERS, node.getStartPosition());
		rewriteRequiredNode(node, ASTNodeConstants.TYPE);
		int pos= rewriteRequiredNode(node, ASTNodeConstants.NAME);
		int extraDims= rewriteExtraDimensions(node, ASTNodeConstants.EXTRA_DIMENSIONS, pos);
		
		if (extraDims > 0) {
			int kind= getChangeKind(node, ASTNodeConstants.INITIALIZER);
			if (kind == RewriteEvent.REMOVED) {
				try {
					pos= getScanner().getPreviousTokenEndOffset(ITerminalSymbols.TokenNameEQUAL, pos);
				} catch (CoreException e) {
					JavaPlugin.log(e);
				}
			} else {
				pos= node.getStartPosition() + node.getLength(); // insert pos
			}
		}
		
		rewriteNode(node, ASTNodeConstants.INITIALIZER, pos, ASTRewriteFormatter.VAR_INITIALIZER);
		return false;
	}

	/* (non-Javadoc)
	 * @see org.eclipse.jdt.core.dom.ASTVisitor#visit(StringLiteral)
	 */
	public boolean visit(StringLiteral node) {
		if (!hasChildrenChanges(node)) {
			return visitChildrenNeeded(node);
		}
		
		changeNotSupported(node); // no modification possible
		return false;
	}

	/* (non-Javadoc)
	 * @see org.eclipse.jdt.core.dom.ASTVisitor#visit(SuperConstructorInvocation)
	 */
	public boolean visit(SuperConstructorInvocation node) {
		if (!hasChildrenChanges(node)) {
			return visitChildrenNeeded(node);
		}
		
		int pos= rewriteOptionalQualifier(node, ASTNodeConstants.EXPRESSION, node.getStartPosition());

		if (isChanged(node, ASTNodeConstants.ARGUMENTS)) {
			// eval position after opening parent
			try {
				pos= getScanner().getTokenEndOffset(ITerminalSymbols.TokenNameLPAREN, pos);
				rewriteNodeList(node, ASTNodeConstants.ARGUMENTS, pos, "", ", "); //$NON-NLS-1$ //$NON-NLS-2$
			} catch (CoreException e) {
				JavaPlugin.log(e);
			}
		} else {
			doVisit(node, ASTNodeConstants.ARGUMENTS, 0);
		}
		return false;		
	}

	/* (non-Javadoc)
	 * @see org.eclipse.jdt.core.dom.ASTVisitor#visit(SuperFieldAccess)
	 */
	public boolean visit(SuperFieldAccess node) {
		if (!hasChildrenChanges(node)) {
			return visitChildrenNeeded(node);
		}
		
		rewriteOptionalQualifier(node, ASTNodeConstants.QUALIFIER, node.getStartPosition());
		rewriteRequiredNode(node, ASTNodeConstants.NAME);
		return false;		
	}

	/* (non-Javadoc)
	 * @see org.eclipse.jdt.core.dom.ASTVisitor#visit(SuperMethodInvocation)
	 */
	public boolean visit(SuperMethodInvocation node) {
		if (!hasChildrenChanges(node)) {
			return visitChildrenNeeded(node);
		}
		
		rewriteOptionalQualifier(node, ASTNodeConstants.QUALIFIER, node.getStartPosition());
		
		int pos= rewriteRequiredNode(node, ASTNodeConstants.NAME);
		
		if (isChanged(node, ASTNodeConstants.ARGUMENTS)) {
			// eval position after opening parent
			try {
				pos= getScanner().getTokenEndOffset(ITerminalSymbols.TokenNameLPAREN, pos);
				rewriteNodeList(node, ASTNodeConstants.ARGUMENTS, pos, "", ", "); //$NON-NLS-1$ //$NON-NLS-2$
			} catch (CoreException e) {
				JavaPlugin.log(e);
			}
		} else {
			doVisit(node, ASTNodeConstants.ARGUMENTS, 0);
		}		
		return false;	
	}

	/* (non-Javadoc)
	 * @see org.eclipse.jdt.core.dom.ASTVisitor#visit(SwitchCase)
	 */
	public boolean visit(SwitchCase node) {
		if (!hasChildrenChanges(node)) {
			return visitChildrenNeeded(node);
		}
		
		// dont allow switching from case to default or back. New statements should be created.
		rewriteRequiredNode(node, ASTNodeConstants.EXPRESSION);
		return false;
	}

	private class SwitchListRewriter extends ParagraphListRewriter {

		public SwitchListRewriter(int initialIndent) {
			super(initialIndent, 0);
		}
		
		protected int getNodeIndent(int nodeIndex) {
			int indent= getInitialIndent();
			ASTNode node= (ASTNode) fList[nodeIndex].getOriginalValue();
			if (node == null) {
				node= (ASTNode) fList[nodeIndex].getNewValue();
			}
			if (node.getNodeType() != ASTNode.SWITCH_CASE) {
				indent++;
			}
			return indent;
		}		
	}

	/* (non-Javadoc)
	 * @see org.eclipse.jdt.core.dom.ASTVisitor#visit(SwitchStatement)
	 */
	public boolean visit(SwitchStatement node) {
		if (!hasChildrenChanges(node)) {
			return visitChildrenNeeded(node);
		}
		
		int pos= rewriteRequiredNode(node, ASTNodeConstants.EXPRESSION);
		
		RewriteEvent event= getEvent(node, ASTNodeConstants.STATEMENTS);
		if (event != null && event.getChangeKind() != RewriteEvent.UNCHANGED) {
			try {
				pos= getScanner().getTokenEndOffset(ITerminalSymbols.TokenNameLBRACE, pos);
				int insertIndent= getIndent(node.getStartPosition()) + 1;
				
				ParagraphListRewriter listRewriter= new SwitchListRewriter(insertIndent);
				StringBuffer leadString= new StringBuffer();
				leadString.append(getLineDelimiter());
				leadString.append(CodeFormatterUtil.createIndentString(insertIndent));
				listRewriter.rewriteList(event.getChildren(), pos, leadString.toString());				
			} catch (CoreException e) {
				JavaPlugin.log(e);
			}
		} else {
			doVisit(node, ASTNodeConstants.STATEMENTS, 0);
		}
		return false;		
	}

	/* (non-Javadoc)
	 * @see org.eclipse.jdt.core.dom.ASTVisitor#visit(SynchronizedStatement)
	 */
	public boolean visit(SynchronizedStatement node) {
		if (!hasChildrenChanges(node)) {
			return visitChildrenNeeded(node);
		}
		
		rewriteRequiredNode(node, ASTNodeConstants.EXPRESSION);
		rewriteRequiredNode(node, ASTNodeConstants.BODY);
		return false;
	}

	/* (non-Javadoc)
	 * @see org.eclipse.jdt.core.dom.ASTVisitor#visit(ThisExpression)
	 */
	public boolean visit(ThisExpression node) {
		if (!hasChildrenChanges(node)) {
			return visitChildrenNeeded(node);
		}
		
		rewriteOptionalQualifier(node, ASTNodeConstants.QUALIFIER, node.getStartPosition());
		return false;		
	}

	/* (non-Javadoc)
	 * @see org.eclipse.jdt.core.dom.ASTVisitor#visit(ThrowStatement)
	 */
	public boolean visit(ThrowStatement node) {
		if (!hasChildrenChanges(node)) {
			return visitChildrenNeeded(node);
		}
		
		rewriteRequiredNode(node, ASTNodeConstants.EXPRESSION);		
		return false;	
	}

	/* (non-Javadoc)
	 * @see org.eclipse.jdt.core.dom.ASTVisitor#visit(TryStatement)
	 */
	public boolean visit(TryStatement node) {
		if (!hasChildrenChanges(node)) {
			return visitChildrenNeeded(node);
		}
		
		int pos= rewriteRequiredNode(node, ASTNodeConstants.BODY);
		
		if (isChanged(node, ASTNodeConstants.CATCH_CLAUSES)) {
			int indent= getIndent(node.getStartPosition());
			String prefix= ASTRewriteFormatter.CATCH_BLOCK.getPrefix(indent, getLineDelimiter());
			pos= rewriteNodeList(node, ASTNodeConstants.CATCH_CLAUSES, pos, prefix, prefix);
		} else {
			pos= doVisit(node, ASTNodeConstants.CATCH_CLAUSES, pos);
		}
		rewriteNode(node, ASTNodeConstants.FINALLY, pos, ASTRewriteFormatter.FINALLY_BLOCK);
		return false;
	}



	/* (non-Javadoc)
	 * @see org.eclipse.jdt.core.dom.ASTVisitor#visit(TypeDeclarationStatement)
	 */
	public boolean visit(TypeDeclarationStatement node) {
		if (!hasChildrenChanges(node)) {
			return visitChildrenNeeded(node);
		}
		
		rewriteRequiredNode(node, ASTNodeConstants.TYPE_DECLARATION);	
		return false;
	}

	/* (non-Javadoc)
	 * @see org.eclipse.jdt.core.dom.ASTVisitor#visit(TypeLiteral)
	 */
	public boolean visit(TypeLiteral node) {
		if (!hasChildrenChanges(node)) {
			return visitChildrenNeeded(node);
		}
		
		rewriteRequiredNode(node, ASTNodeConstants.TYPE);
		return false;
	}

	/* (non-Javadoc)
	 * @see org.eclipse.jdt.core.dom.ASTVisitor#visit(VariableDeclarationExpression)
	 */
	public boolean visit(VariableDeclarationExpression node) {
		if (!hasChildrenChanges(node)) {
			return visitChildrenNeeded(node);
		}
		
		// same code as FieldDeclaration
		rewriteModifiers(node, ASTNodeConstants.MODIFIERS, node.getStartPosition());		
		int pos= rewriteRequiredNode(node, ASTNodeConstants.TYPE);
		rewriteNodeList(node, ASTNodeConstants.FRAGMENTS, pos, "", ", "); //$NON-NLS-1$ //$NON-NLS-2$
		return false;
	}

	/* (non-Javadoc)
	 * @see org.eclipse.jdt.core.dom.ASTVisitor#visit(VariableDeclarationFragment)
	 */
	public boolean visit(VariableDeclarationFragment node) {
		if (!hasChildrenChanges(node)) {
			return visitChildrenNeeded(node);
		}
		
		int pos= rewriteRequiredNode(node, ASTNodeConstants.NAME);
	
		int extraDims= rewriteExtraDimensions(node, ASTNodeConstants.EXTRA_DIMENSIONS, pos);
		
		if (extraDims > 0) {
			int kind= getChangeKind(node, ASTNodeConstants.INITIALIZER);
			if (kind == RewriteEvent.REMOVED) {
				try {
					pos= getScanner().getPreviousTokenEndOffset(ITerminalSymbols.TokenNameEQUAL, pos);
				} catch (CoreException e) {
					JavaPlugin.log(e);
				}
			} else {
				pos= node.getStartPosition() + node.getLength(); // insert pos
			}
		}
		rewriteNode(node, ASTNodeConstants.INITIALIZER, pos, ASTRewriteFormatter.VAR_INITIALIZER);
		return false;
	}

	/* (non-Javadoc)
	 * @see org.eclipse.jdt.core.dom.ASTVisitor#visit(VariableDeclarationStatement)
	 */
	public boolean visit(VariableDeclarationStatement node) {
		if (!hasChildrenChanges(node)) {
			return visitChildrenNeeded(node);
		}
		
		// same code as FieldDeclaration		
		rewriteModifiers(node, ASTNodeConstants.MODIFIERS, node.getStartPosition());
		int pos= rewriteRequiredNode(node, ASTNodeConstants.TYPE);
		
		rewriteNodeList(node, ASTNodeConstants.FRAGMENTS, pos, "", ", "); //$NON-NLS-1$ //$NON-NLS-2$
		return false;
	}

	/* (non-Javadoc)
	 * @see org.eclipse.jdt.core.dom.ASTVisitor#visit(WhileStatement)
	 */
	public boolean visit(WhileStatement node) {
		if (!hasChildrenChanges(node)) {
			return visitChildrenNeeded(node);
		}
		
		int pos= rewriteRequiredNode(node, ASTNodeConstants.EXPRESSION);
		
		try {
			if (isChanged(node, ASTNodeConstants.BODY)) {
				int startOffset= getScanner().getTokenEndOffset(ITerminalSymbols.TokenNameRPAREN, pos);
				rewriteBodyNode(node, ASTNodeConstants.BODY, startOffset, -1, getIndent(node.getStartPosition()), ASTRewriteFormatter.WHILE_BLOCK); // body
			} else {
				doVisit(node, ASTNodeConstants.BODY, 0);
			}
		} catch (CoreException e) {
			JavaPlugin.log(e);
		}
		return false;
	}

}

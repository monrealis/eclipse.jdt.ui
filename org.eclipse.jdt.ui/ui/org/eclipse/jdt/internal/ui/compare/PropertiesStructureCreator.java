/*
 * (c) Copyright IBM Corp. 2000, 2001.
 * All Rights Reserved.
 */
package org.eclipse.jdt.internal.ui.compare;

import java.io.IOException;

import org.eclipse.swt.graphics.Image;

import org.eclipse.jface.text.*;

import org.eclipse.compare.*;
import org.eclipse.compare.structuremergeviewer.*;
import org.eclipse.core.runtime.CoreException;


public class PropertiesStructureCreator implements IStructureCreator {
	
	static class PropertyNode extends DocumentRangeNode implements ITypedElement {
		
		private String fValue;
		private boolean fIsEditable;
		
		public PropertyNode(PropertyNode parent, int type, String id, String value, IDocument doc, int start, int length) {
			super(type, id, doc, start, length);
			fValue= value;
			if (parent != null) {
				parent.addChild(this);
				fIsEditable= parent.isEditable();
			}
		}
						
		public PropertyNode(IDocument doc, boolean editable) {
			super(0, "root", doc, 0, doc.getLength()); //$NON-NLS-1$
			fValue= ""; //$NON-NLS-1$
			fIsEditable= editable;
		}
				
		/**
		 * @see ITypedElement#getName
		 */
		public String getName() {
			return this.getId();
		}

		/**
		 * @see ITypedElement#getType
		 */
		public String getType() {
			return "txt"; //$NON-NLS-1$
		}
		
		/**
		 * @see ITypedElement#getImage
		 */
		public Image getImage() {
			return CompareUI.getImage(getType());
		}
		
		/* (non Javadoc)
		 * see IEditableContent.isEditable
		 */
		public boolean isEditable() {
			return fIsEditable;
		}
	};
	
	private static final String WHITESPACE= " \t\r\n\f"; //$NON-NLS-1$
	private static final String SEPARATORS2= "=:"; //$NON-NLS-1$
	private static final String SEPARATORS= SEPARATORS2 + WHITESPACE;
			

	public PropertiesStructureCreator() {
	}
	
	public String getName() {
		return CompareMessages.getString("PropertyCompareViewer.title"); //$NON-NLS-1$
	}

	public IStructureComparator getStructure(Object input) {
		
		String s= null;
		if (input instanceof IStreamContentAccessor) {
			try {
				s= JavaCompareUtilities.readString(((IStreamContentAccessor) input).getContents());
			} catch(CoreException ex) {
			}
		}
			
		Document doc= new Document(s != null ? s : ""); //$NON-NLS-1$
				
		boolean isEditable= false;
		if (input instanceof IEditableContent)
			isEditable= ((IEditableContent) input).isEditable();

		PropertyNode root= new PropertyNode(doc, isEditable);		
				
		try {
			load(root, doc);
		} catch (IOException ex) {
		}
		
		return root;
	}
	
	public boolean canSave() {
		return true;
	}
	
	public void save(IStructureComparator structure, Object input) {
		if (input instanceof IEditableContent && structure instanceof PropertyNode) {
			IDocument doc= ((PropertyNode)structure).getDocument();
			IEditableContent bca= (IEditableContent) input;
			String c= doc.get();
			bca.setContent(c.getBytes());
		}
	}
		
	public IStructureComparator locate(Object path, Object source) {
		return null;
	}
	
	public boolean canRewriteTree() {
		return false;
	}
	
	public void rewriteTree(Differencer differencer, IDiffContainer root) {
	}
	
	public String getContents(Object node, boolean ignoreWhitespace) {
		if (node instanceof IStreamContentAccessor) {
			IStreamContentAccessor sca= (IStreamContentAccessor) node;
			try {
				return JavaCompareUtilities.readString(sca.getContents());
			} catch (CoreException ex) {
			}
		}
		return null;
	}
	
	private String readLine(int[] args, IDocument doc) {
		int line= args[0]++;
		try {
			IRegion region= doc.getLineInformation(line);
			int start= region.getOffset();
			int length= region.getLength();
			
			try {
				region= doc.getLineInformation(line+1);
				args[1]= region.getOffset();
			} catch (BadLocationException ex) {
				args[1]= doc.getLength();
			}
			
			return doc.get(start, length);
		} catch (BadLocationException ex) {
		}
		return null;
	}
			
	private void load(PropertyNode root, IDocument doc) throws IOException {
		
		int start= 0;
		
		int[] args= new int[2];
		args[0]= 1;
		args[1]= 0;
		
		for (;;) {
            String line= readLine(args, doc);
			if (line == null)
				return;

			if (line.length() <= 0)
				continue;	// empty line
				
			char firstChar= line.charAt(0);
			if (firstChar == '#' || firstChar == '!')
				continue;	// comment
								
			// find continuation lines
			while (continueLine(line)) {
				String nextLine= readLine(args, doc);
				if (nextLine == null)
					nextLine= ""; //$NON-NLS-1$
				String line2= line.substring(0, line.length()-1);
				int startPos= 0;
				for (; startPos < nextLine.length(); startPos++)
					if (WHITESPACE.indexOf(nextLine.charAt(startPos)) == -1)
						break;
				nextLine= nextLine.substring(startPos, nextLine.length());
				line= line2 + nextLine;
			}
			
    		// key start
    		int len= line.length();
    		int keyPos= 0;
    		for (; keyPos < len; keyPos++) {
       			if (WHITESPACE.indexOf(line.charAt(keyPos)) == -1)
            		break;
    		}
    		
    		// key/value separator
    		int separatorPos;
    		for (separatorPos= keyPos; separatorPos < len; separatorPos++) {
        		char c= line.charAt(separatorPos);
        		if (c == '\\')
            		separatorPos++;
        		else if (SEPARATORS.indexOf(c) != -1)
            		break;
    		}

     		int valuePos;
    		for (valuePos= separatorPos; valuePos < len; valuePos++)
        		if (WHITESPACE.indexOf(line.charAt(valuePos)) == -1)
            		break;

     		if (valuePos < len)
        		if (SEPARATORS2.indexOf(line.charAt(valuePos)) != -1)
            		valuePos++;

     		while (valuePos < len) {
        		if (WHITESPACE.indexOf(line.charAt(valuePos)) == -1)
            		break;
        		valuePos++;
    		}
    
    		String key= line.substring(keyPos, separatorPos);
    		String value= (separatorPos < len) ? line.substring(valuePos, len) : ""; //$NON-NLS-1$

    		key= convert(key);
    		value= convert(value);
    		
    		int length= (args[1]-1) - start;
     		new PropertyNode(root, 0, key, value, doc, start, length);
             
			start= args[1];
		}
	}

	private boolean continueLine(String line) {
		int slashes= 0;
		int ix= line.length() - 1;
		while ((ix >= 0) && (line.charAt(ix--) == '\\'))
			slashes++;
		return slashes % 2 == 1;
	}

	/*
	 * Converts escaped characters to Unicode.
	 */
	private String convert(String s) {
		int l= s.length();
		StringBuffer buf= new StringBuffer(l);
		int i= 0;
		
		while (i < l) {
			char c= s.charAt(i++);
			if (c == '\\') {
				c= s.charAt(i++);
				if (c == 'u') {
					int v= 0;
					for (int j= 0; j < 4; j++) {
						c= s.charAt(i++);
				        switch (c) {
				        case '0': case '1': case '2': case '3': case '4':
				        case '5': case '6': case '7': case '8': case '9':
							v= (v << 4) + (c-'0');
					     	break;
						case 'a': case 'b': case 'c':
		     			case 'd': case 'e': case 'f':
							v= (v << 4) + 10+(c-'a');
							break;
						case 'A': case 'B': case 'C':
		                case 'D': case 'E': case 'F':
							v= (v << 4) + 10+(c - 'A');
							break;
						default:
		             		throw new IllegalArgumentException(CompareMessages.getString("PropertyCompareViewer.malformedEncoding")); //$NON-NLS-1$
		                }
					}
					buf.append((char)v);
				} else {
					switch (c) {
					case 't':
		    			c= '\t';
						break;
					case 'r':
		    			c= '\r';
						break;
					case 'n':
		    			c= '\n';
						break;
					case 'f':
		    			c= '\f';
						break;
					}
		            buf.append(c);
				}
			} else
				buf.append(c);
		}
		return buf.toString();
	}
}

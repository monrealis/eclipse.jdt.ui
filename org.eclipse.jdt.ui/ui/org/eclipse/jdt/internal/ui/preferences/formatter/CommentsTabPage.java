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
package org.eclipse.jdt.internal.ui.preferences.formatter;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.Map;
import java.util.Observable;
import java.util.Observer;

import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Group;

import org.eclipse.jdt.core.formatter.DefaultCodeFormatterConstants;


/**
 * Tab page for the comment formatter settings.
 */
public class CommentsTabPage extends ModifyDialogTabPage {
	
	
	private final static class Controller implements Observer {
		
		private final Collection<Object> fMasters;
		private final Collection<Object> fSlaves;
		
		public Controller(Collection<Object> masters, Collection<Object> slaves) {
			fMasters= masters;
			fSlaves= slaves;
			for (final Iterator<Object> iter= fMasters.iterator(); iter.hasNext();) {
			    ((CheckboxPreference)iter.next()).addObserver(this);
			}
			update(null, null);
		}

		public void update(Observable o, Object arg) {
		    boolean enabled= true; 

		    for (final Iterator<Object> iter= fMasters.iterator(); iter.hasNext();) {
		        enabled &= ((CheckboxPreference)iter.next()).getChecked();
		    }

			for (final Iterator<Object> iter = fSlaves.iterator(); iter.hasNext();) {
			    final Object obj= iter.next();
			    if (obj instanceof Preference) {
			        ((Preference)obj).setEnabled(enabled);
			    } else if (obj instanceof Control) {
			        ((Group)obj).setEnabled(enabled);
			    }
			}
		}
	}
	
	
	private final static String PREVIEW=
		createPreviewHeader("An example for comment formatting. This example is meant to illustrate the various possibilities offered by <i>Eclipse</i> in order to format comments.") +	//$NON-NLS-1$
		"package mypackage;\n" + //$NON-NLS-1$
		"/**\n" + //$NON-NLS-1$
		" * This is the comment for the example interface.\n" + //$NON-NLS-1$
		" */\n" + //$NON-NLS-1$
		" interface Example {" + //$NON-NLS-1$
		" /**\n" + //$NON-NLS-1$
		" *\n" + //$NON-NLS-1$
		" * These possibilities include:\n" + //$NON-NLS-1$
		" * <ul><li>Formatting of header comments.</li><li>Formatting of Javadoc tags</li></ul>\n" + //$NON-NLS-1$
		" */\n" + //$NON-NLS-1$
		" int bar();" + //$NON-NLS-1$
		" /**\n" + //$NON-NLS-1$
		" * The following is some sample code which illustrates source formatting within javadoc comments:\n" + //$NON-NLS-1$
		" * <pre>public class Example {final int a= 1;final boolean b= true;}</pre>\n" + //$NON-NLS-1$ 
		" * Descriptions of parameters and return values are best appended at end of the javadoc comment.\n" + //$NON-NLS-1$
		" * @param a The first parameter. For an optimum result, this should be an odd number\n" + //$NON-NLS-1$
		" * between 0 and 100.\n" + //$NON-NLS-1$
		" * @param b The second parameter.\n" + //$NON-NLS-1$
		" * @return The result of the foo operation, usually within 0 and 1000.\n" + //$NON-NLS-1$
		" */" + //$NON-NLS-1$
		" int foo(int a, int b);" + //$NON-NLS-1$
		"}"; //$NON-NLS-1$
	
	private CompilationUnitPreview fPreview;

	public CommentsTabPage(ModifyDialog modifyDialog, Map workingValues) {
		super(modifyDialog, workingValues);
	}

	protected void doCreatePreferences(Composite composite, int numColumns) {
	    
		// global group
		final Group globalGroup= createGroup(numColumns, composite, FormatterMessages.CommentsTabPage_group1_title); 
		final CheckboxPreference global= createPrefTrueFalse(globalGroup, numColumns, FormatterMessages.CommentsTabPage_enable_comment_formatting, DefaultCodeFormatterConstants.FORMATTER_COMMENT_FORMAT); 
		final CheckboxPreference header= createPrefTrueFalse(globalGroup, numColumns, FormatterMessages.CommentsTabPage_format_header, DefaultCodeFormatterConstants.FORMATTER_COMMENT_FORMAT_HEADER); 
		final CheckboxPreference html= createPrefTrueFalse(globalGroup, numColumns, FormatterMessages.CommentsTabPage_format_html, DefaultCodeFormatterConstants.FORMATTER_COMMENT_FORMAT_HTML); 
		final CheckboxPreference code= createPrefTrueFalse(globalGroup, numColumns, FormatterMessages.CommentsTabPage_format_code_snippets, DefaultCodeFormatterConstants.FORMATTER_COMMENT_FORMAT_SOURCE); 

		// blank lines group
		final Group settingsGroup= createGroup(numColumns, composite, FormatterMessages.CommentsTabPage_group2_title); 
		final CheckboxPreference blankComments= createPrefTrueFalse(settingsGroup, numColumns, FormatterMessages.CommentsTabPage_clear_blank_lines, DefaultCodeFormatterConstants.FORMATTER_COMMENT_CLEAR_BLANK_LINES); 
		final CheckboxPreference blankJavadoc= createPrefInsert(settingsGroup, numColumns, FormatterMessages.CommentsTabPage_blank_line_before_javadoc_tags, DefaultCodeFormatterConstants.FORMATTER_COMMENT_INSERT_EMPTY_LINE_BEFORE_ROOT_TAGS); 
		final CheckboxPreference indentJavadoc= createPrefTrueFalse(settingsGroup, numColumns, FormatterMessages.CommentsTabPage_indent_javadoc_tags, DefaultCodeFormatterConstants.FORMATTER_COMMENT_INDENT_ROOT_TAGS); 
		
		final CheckboxPreference indentDesc= createCheckboxPref(settingsGroup, numColumns , FormatterMessages.CommentsTabPage_indent_description_after_param, DefaultCodeFormatterConstants.FORMATTER_COMMENT_INDENT_PARAMETER_DESCRIPTION, FALSE_TRUE); 
		((GridData)indentDesc.getControl().getLayoutData()).horizontalIndent= fPixelConverter.convertWidthInCharsToPixels(4);
		final CheckboxPreference nlParam= createPrefInsert(settingsGroup, numColumns, FormatterMessages.CommentsTabPage_new_line_after_param_tags, DefaultCodeFormatterConstants.FORMATTER_COMMENT_INSERT_NEW_LINE_FOR_PARAMETER); 
		
		final Group widthGroup= createGroup(numColumns, composite, FormatterMessages.CommentsTabPage_group3_title); 
		final NumberPreference lineWidth= createNumberPref(widthGroup, numColumns, FormatterMessages.CommentsTabPage_line_width, DefaultCodeFormatterConstants.FORMATTER_COMMENT_LINE_LENGTH, 0, 9999); 

		Collection<Object> masters, slaves;

		masters= new ArrayList<Object>();
		masters.add(global);
		
		slaves= new ArrayList<Object>();
		slaves.add(settingsGroup);
		slaves.add(header);
		slaves.add(html);
		slaves.add(code);
		slaves.add(blankComments);
		slaves.add(blankJavadoc);
		slaves.add(indentJavadoc);
		slaves.add(nlParam);
		slaves.add(lineWidth);
		
		new Controller(masters, slaves);
		
		masters= new ArrayList<Object>();
		masters.add(global);
		masters.add(indentJavadoc);
		
		slaves= new ArrayList<Object>();
		slaves.add(indentDesc);
		
		new Controller(masters, slaves);
	}
	
	protected void initializePage() {
		fPreview.setPreviewText(PREVIEW);
	}
	
    /* (non-Javadoc)
     * @see org.eclipse.jdt.internal.ui.preferences.formatter.ModifyDialogTabPage#doCreateJavaPreview(org.eclipse.swt.widgets.Composite)
     */
    protected JavaPreview doCreateJavaPreview(Composite parent) {
        fPreview= new CompilationUnitPreview(fWorkingValues, parent);
        return fPreview;
    }

    /* (non-Javadoc)
     * @see org.eclipse.jdt.internal.ui.preferences.formatter.ModifyDialogTabPage#doUpdatePreview()
     */
    protected void doUpdatePreview() {
        fPreview.update();
    }
	
	private CheckboxPreference createPrefTrueFalse(Composite composite, int numColumns, String text, String key) {
		return createCheckboxPref(composite, numColumns, text, key, FALSE_TRUE);
	}
    
    private CheckboxPreference createPrefInsert(Composite composite, int numColumns, String text, String key) {
        return createCheckboxPref(composite, numColumns, text, key, DO_NOT_INSERT_INSERT);
    }
}

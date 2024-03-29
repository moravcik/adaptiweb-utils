package com.adaptiweb.utils.csvbind;

import java.beans.PropertyDescriptor;
import java.beans.PropertyEditor;
import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.util.Iterator;
import java.util.NoSuchElementException;

import org.apache.commons.lang.StringUtils;

import au.com.bytecode.opencsv.CSVReader;
import au.com.bytecode.opencsv.bean.CsvToBean;
import au.com.bytecode.opencsv.bean.MappingStrategy;

import com.adaptiweb.utils.commons.StringUtils.StringArraySource;
import com.adaptiweb.utils.csvbind.annotation.CsvField;

/**
 * CSV files reader based on opencsv {@link CSVReader}
 * provides iterator over CSV records mapped to beans of type T.
 * CSV -> bean gsm is defined in T class by {@link CsvField} annotations.
 * 
 * Extends {@link CsvToBean} class in order to reuse protected {@link #processLine(MappingStrategy, String[])} method.
 * 
 * @param <T> target bean class
 */
public class CsvReader<T> extends CsvToBean<T> implements Iterable<T> {
    private StringArraySource source;
    private T nextRecord = null;
    private final T errorRecord;
    /**
     * Stores next line of parsing.
     */
    private String nextLine;
    /**
     * Bean strategy
     */
    protected CsvFieldMapping<T> mapping;
    
    /**
     * Aligning of parsed line.
     */
    protected LineAlign alignType;
    
    public enum LineAlign {
    	DEFAULT, // no change to line
    	LEFT;    // align left to first not-empty cell
    }
    
    private static class CSVArraySource implements StringArraySource {
    	CSVReader reader;

		private CSVArraySource(CSVReader reader) {
			this.reader = reader;
		}

		@Override
		public String[] getStringArray() {
			try {
				return reader.readNext();
			} catch (IOException e) {
				throw new RuntimeException(e);
			}
		}
		
		void close() throws IOException {
			reader.close();
		}
    }
    
    /**
     * Constructor initializes {@link CSVReader} and gsm strategy.
     * If beanClass is not annotated with {@link CsvField} annotations for gsm CSV lines,
     * runtime exception IllegalArgumentException is thrown.
     * 
     * @param reader CSV content reader
     * @param beanClass target bean class annotated with {@link CsvField} annotations
     * @param align type of aligning parsed line.
     * @param separator CSV value separator
     * @param quotechar CSV quote character if separator is present in value
     *                  if quote character is present in value it is doubled.
     */
    public CsvReader(Reader reader, Class<T> beanClass, LineAlign align, char separator, char quotechar) {
    	this(beanClass, align);
        this.source = new CSVArraySource(new CSVReader(reader, separator, quotechar));
    }

    /**
     * Constructor initializes {@link CSVReader} and gsm strategy.
     * If beanClass is not annotated with {@link CsvField} annotations for gsm CSV lines,
     * runtime exception IllegalArgumentException is thrown.
     * 
     * @param reader CSV content reader
     * @param beanClass target bean class annotated with {@link CsvField} annotations
     * @param align type of aligning parsed line.
     */
    public CsvReader(Reader reader, Class<T> beanClass, LineAlign align) {
    	this(reader, beanClass, align, CsvConstants.EXCEL_SEPARATOR, CsvConstants.EXCEL_QUOTECHAR);
    }

    /**
     * Constructor initializes {@link CSVReader} and gsm strategy.
     * If beanClass is not annotated with {@link CsvField} annotations for gsm CSV lines,
     * runtime exception IllegalArgumentException is thrown.
     * Default Excel-like separator and quotechar is used.
     * 
     * @param reader CSV content reader
     * @param beanClass target bean class annotated with {@link CsvField} annotations
     */
    public CsvReader(Reader reader, Class<T> beanClass) {
    	this(reader, beanClass, LineAlign.DEFAULT, CsvConstants.EXCEL_SEPARATOR, CsvConstants.EXCEL_QUOTECHAR);
    }

    /**
     * Private constructor, assuming calling public constructor sets {@link #reader} field.
     */
    private CsvReader(Class<T> beanClass, LineAlign align) {
        try {
			errorRecord = beanClass.newInstance(); // empty bean instance to early determine line parsing error
		} catch (InstantiationException e) {
			throw new IllegalArgumentException("Error initializing CSV bean: "+ e.getMessage(), e);
		} catch (IllegalAccessException e) {
			throw new IllegalArgumentException("Error initializing CSV bean: "+ e.getMessage(), e);
		}
        alignType = align;
        mapping = new CsvFieldMapping<T>();
        mapping.setType(beanClass);
    }
    
    /**
     * Constructor initializes gsm strategy with use of custom {@link StringArraySource}.
     * If beanClass is not annotated with {@link CsvField} annotations for gsm CSV lines,
     * runtime exception IllegalArgumentException is thrown.
     * 
     * @param source custom implementation of {@link StringArraySource}
     * @param beanClass target bean class annotated with {@link CsvField} annotations
     * @param align type of aligning parsed line.
     */
    public CsvReader(StringArraySource source, Class<T> beanClass, LineAlign align) {
    	this(beanClass, align);
    	this.source = source;
    }

    /**
     * Constructor initializes gsm strategy with use of custom {@link StringArraySource}.
     * If beanClass is not annotated with {@link CsvField} annotations for gsm CSV lines,
     * runtime exception IllegalArgumentException is thrown.
     * 
     * @param source custom implementation of {@link StringArraySource}
     * @param beanClass target bean class annotated with {@link CsvField} annotations
     */
    public CsvReader(StringArraySource source, Class<T> beanClass) {
    	this(source, beanClass, LineAlign.DEFAULT);
    }
    
    /**
     * Read next line from CSV, perform bean gsm and store as nextRecord.
     * When end of CSV is reached null is stored as nextRecord.
     */
    private void loadNextRecord() {
    	mapping.initMapping(); // clear list cache
    	
    	String[] line = null;
        try {
            line = source.getStringArray();
            if (line != null) {                
                nextLine = "";
                for (int i = 0; i < line.length; i++) { // hack to get line of parsing (for callers of parsing process)
                    if (i > 0) nextLine += CsvConstants.EXCEL_SEPARATOR;
                    nextLine += line[i];
                }
            	if (removeComment(line) || isEmptyLine(line)) loadNextRecord(); // returns
            	else {
            		Integer alignIndex = 0;
            		if (alignType.equals(LineAlign.LEFT)) alignIndex = alignLineLeft(line);
            		
            		nextRecord = (T) processLine(mapping, line);
            		if (nextRecord instanceof CsvAlignIndex) {
            			((CsvAlignIndex) nextRecord).setAlignIndex(alignIndex);
            		}
            	}
            } else {
                nextRecord = null;
            }
        } catch (Exception e) {
            // LOG.error("Error parsing CSV: " + e.getMessage(), e);
            nextRecord = errorRecord;
            if (e instanceof CsvException) throw (CsvException) e;
            else throw new CsvException("Error parsing CSV: " + e.getMessage(), e, line);
        }
    }
    
    /**
     * Shift left all cell values to begin with not empty value
     * @param line
     */
    protected Integer alignLineLeft(String[] line) {
    	Integer alignIndex = 0;
    	// find shift level
		for (int i = 0; i < line.length; i++) {
			if (line[i] != null && line[i].trim().length() > 0) {
				alignIndex = i;
				break;
			}
		}
		if (alignIndex > 0) { // shift cells to align left
			for (int i = alignIndex; i < line.length; i++) {
				line[i - alignIndex] = line[i];
				line[i] = "";
			}
		}
		return alignIndex;
	}

	/**
     * Check if all values in line are empty
     * @param line
     * @return
     */
    protected boolean isEmptyLine(String[] line) {
    	for (int i = 0; i < line.length; i++)
    		if (line[i] != null && line[i].trim().length() > 0) return false;
		return true;
	}

	/**
     * Clear all values after COMMENT_CHAR.
     * Comment is valid if COMMENT_CHAR is present as first character in cell.
     * @param line
     * @return Returns if comment was present
     */
    protected boolean removeComment(String[] line) {
    	boolean commentLine = false;
    	for (int i = 0; i < line.length; i++) {
    		String value = line[i].trim();
    		if (value != null && value.trim().length() > 0
    				&& value.charAt(0) == CsvConstants.COMMENT_CHAR) commentLine = true;
			if (commentLine) line[i] = "";
    	}
		return commentLine;
	}

	/**
     * Close underlying resources.
     */
	public void closeResources() {
        if (source != null && source instanceof CSVArraySource) {
    		try {
            	((CSVArraySource) source).close();
            } catch (IOException e) {
            	e.printStackTrace();
            }
        }
    }
    
    /**
     * Returns iterator of T beans over CSV. {@link Iterator#remove()} operation is not supported.
     */
    public Iterator<T> iterator() {
        return new Iterator<T>() {
        	private boolean initial = true;
            public boolean hasNext() {
            	if (initial || nextRecord == errorRecord) { // intentionally "==" comparing
            		initial = false;
            		loadNextRecord();
            	}
                return nextRecord != null;
            }
            public T next() {
                if (hasNext()) {
                    try {
                        return nextRecord;
                    } finally {
                        loadNextRecord();
                    }
                } else throw new NoSuchElementException();
            }
            public void remove() {
                throw new UnsupportedOperationException("remove() method is not supported");
            }
        };
    }

    @Override
    protected PropertyEditor getPropertyEditor(PropertyDescriptor propertydescriptor) throws InstantiationException, IllegalAccessException {
    	return mapping.getDescriptor(propertydescriptor.getName()).getFieldEditor();
    }
    
    @Override
    protected Object convertValue(String value, PropertyDescriptor prop) throws InstantiationException, IllegalAccessException {
    	boolean emptyAsNull = mapping.getDescriptor(prop.getName()).getFieldAnnotation().emtyAsNull();
    	return emptyAsNull && StringUtils.isEmpty(value) ? null : super.convertValue(value, prop);
    }
    
    /**
     * Read single line into single T bean
     * 
     * @param <T> type of csv bean
     * @param csvLine single line containing serialized csv bean
     * @param beanClass T.class
     * 
     * @throws IllegalArgumentException if there is a problem instantiating beanClass
     *         or CsvException if there is problem parsing csvLine string
     */
    public static <T> T readLine(String csvLine, Class<T> beanClass) throws CsvException {
    	if (csvLine == null || csvLine.length() == 0) return null;
    	
    	CsvReader<T> csvReader = new CsvReader<T>(new StringReader(csvLine), beanClass);
    	try {
	    	csvReader.loadNextRecord();
	    	return csvReader.nextRecord;
    	} finally {
    		csvReader.closeResources();
    	}
    }
    
}

/*! ******************************************************************************
*
* Pentaho Data Integration
*
* Copyright (C) 2002-2013 by Pentaho : http://www.pentaho.com
*
*******************************************************************************
*
* Licensed under the Apache License, Version 2.0 (the "License");
* you may not use this file except in compliance with
* the License. You may obtain a copy of the License at
*
*    http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*
******************************************************************************/

package org.pentaho.di.ui.core.database.dialog;

import java.lang.reflect.InvocationTargetException;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jface.dialogs.ProgressMonitorDialog;
import org.eclipse.jface.operation.IRunnableWithProgress;
import org.eclipse.swt.widgets.Shell;
import org.pentaho.di.core.RowMetaAndData;
import org.pentaho.di.core.database.Database;
import org.pentaho.di.core.database.DatabaseMeta;
import org.pentaho.di.core.exception.KettleException;
import org.pentaho.di.i18n.BaseMessages;
import org.pentaho.di.ui.core.dialog.ErrorDialog;
import org.pentaho.di.ui.spoon.Spoon;


/**
 * Takes care of displaying a dialog that will handle the wait while 
 * we're getting the number of rows for a certain table in a database.
 * 
 * @author Matt
 * @since  12-may-2005
 */
public class GetTableSizeProgressDialog
{
	private static Class<?> PKG = GetTableSizeProgressDialog.class; // for i18n purposes, needed by Translator2!!   $NON-NLS-1$

	private Shell shell;
	private DatabaseMeta dbMeta;
	private String tableName;
	private Long size;
	
	private Database db;
    
	/**
	 * Creates a new dialog that will handle the wait while we're doing the hard work.
	 */
  public GetTableSizeProgressDialog(Shell shell, DatabaseMeta dbInfo, String tableName) {
    this(shell, dbInfo, tableName, null);
  }
	public GetTableSizeProgressDialog(Shell shell, DatabaseMeta dbInfo, String tableName, String schemaName)
	{
		this.shell = shell;
		this.dbMeta = dbInfo;
		this.tableName = dbInfo.getQuotedSchemaTableCombination(schemaName, tableName);
	}
	
	public Long open()
	{
		IRunnableWithProgress op = new IRunnableWithProgress()
		{
			public void run(IProgressMonitor monitor) throws InvocationTargetException, InterruptedException
			{
				db = new Database(Spoon.loggingObject, dbMeta);
				try 
				{
					db.connect();
		
					String sql= dbMeta.getDatabaseInterface().getSelectCountStatement(tableName);
					RowMetaAndData row =  db.getOneRow(sql);
                    size = row.getRowMeta().getInteger(row.getData(), 0);
					
					if (monitor.isCanceled()) 
						throw new InvocationTargetException(new Exception("This operation was cancelled!"));

				}
				catch(KettleException e)
				{
					throw new InvocationTargetException(e, "Couldn't get a result because of an error :"+e.toString());
				}
				finally
				{
					db.disconnect();
				}
			}
		};
		
		try
		{
			final ProgressMonitorDialog pmd = new ProgressMonitorDialog(shell);
			// Run something in the background to cancel active database queries, forecably if needed!
			Runnable run = new Runnable()
            {
                public void run()
                {
                    IProgressMonitor monitor = pmd.getProgressMonitor();
                    while (pmd.getShell()==null || ( !pmd.getShell().isDisposed() && !monitor.isCanceled() ))
                    {
                        try { Thread.sleep(100); } catch(InterruptedException e) {
                          // Ignore
                        }
                    }
                    
                    if (monitor.isCanceled()) // Disconnect and see what happens!
                    {
                        try { db.cancelQuery(); } catch(Exception e) {
                          // Ignore
                        }
                    }
                }
            };
            // Start the cancel tracker in the background!
            new Thread(run).start();
            
			pmd.run(true, true, op);
		}
		catch (InvocationTargetException e)
		{
			showErrorDialog(e);
			return null;
		}
		catch (InterruptedException e)
		{
            showErrorDialog(e);
			return null;
		}
		
		return size;
	}

    /**
     * Showing an error dialog
     * 
     * @param e
    */
    private void showErrorDialog(Exception e)
    {
        new ErrorDialog(shell, BaseMessages.getString(PKG, "GetTableSizeProgressDialog.Error.Title"),
            BaseMessages.getString(PKG, "GetTableSizeProgressDialog.Error.Message"), e);
    }
}

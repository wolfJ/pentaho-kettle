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

package org.pentaho.di.trans.steps.autodoc;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

import javax.imageio.ImageIO;

import org.apache.commons.vfs.FileObject;
import org.pentaho.di.core.ResultFile;
import org.pentaho.di.core.exception.KettleException;
import org.pentaho.di.core.gui.AreaOwner;
import org.pentaho.di.core.row.RowDataUtil;
import org.pentaho.di.core.vfs.KettleVFS;
import org.pentaho.di.i18n.BaseMessages;
import org.pentaho.di.job.JobMeta;
import org.pentaho.di.repository.RepositoryDirectory;
import org.pentaho.di.repository.RepositoryDirectoryInterface;
import org.pentaho.di.repository.RepositoryObjectType;
import org.pentaho.di.trans.Trans;
import org.pentaho.di.trans.TransMeta;
import org.pentaho.di.trans.step.BaseStep;
import org.pentaho.di.trans.step.StepDataInterface;
import org.pentaho.di.trans.step.StepInterface;
import org.pentaho.di.trans.step.StepMeta;
import org.pentaho.di.trans.step.StepMetaInterface;
import org.pentaho.di.trans.steps.autodoc.KettleReportBuilder.OutputType;
import org.pentaho.reporting.engine.classic.core.ClassicEngineBoot;
import org.pentaho.reporting.libraries.base.util.ObjectUtilities;
import org.pentaho.reporting.libraries.fonts.LibFontBoot;
import org.pentaho.reporting.libraries.resourceloader.LibLoaderBoot;

/**
 * Reads a set of transformation and job filenames and documents those.
 * 
 * @author Matt
 * @since 2010-mar-14
 */
public class AutoDoc extends BaseStep implements StepInterface
{
	private static Class<?> PKG = AutoDoc.class; // for i18n purposes, needed by Translator2!!   $NON-NLS-1$

	private AutoDocMeta meta;
	private AutoDocData data;
	
	public AutoDoc(StepMeta stepMeta, StepDataInterface stepDataInterface, int copyNr, TransMeta transMeta, Trans trans)
	{
		super(stepMeta, stepDataInterface, copyNr, transMeta, trans);
	}
	
	public boolean processRow(StepMetaInterface smi, StepDataInterface sdi) throws KettleException
	{
		meta=(AutoDocMeta)smi;
		data=(AutoDocData)sdi;

		Object[] row = getRow();
		if (row==null) {
			
			if (data.filenames.isEmpty()) {
				// Nothing to see here, move along!
				//
			  setOutputDone();
				return false;
			}
			
			// End of the line, create the documentation...
			//
			FileObject targetFile = KettleVFS.getFileObject( environmentSubstitute(meta.getTargetFilename()) );
			String targetFilename = KettleVFS.getFilename(targetFile);
						
			// Create the report builder
			//
			KettleReportBuilder kettleReportBuilder = new KettleReportBuilder(this, data.filenames, KettleVFS.getFilename(targetFile), meta);
			
			try {
				// Try to get the Classic Reporting Engine to boot inside of the plugin class loader...
				//
				if (ClassicEngineBoot.getInstance().isBootDone() == false){
					
					ObjectUtilities.setClassLoader(getClass().getClassLoader());
					ObjectUtilities.setClassLoaderSource(ObjectUtilities.CLASS_CONTEXT);
					
					LibLoaderBoot.getInstance().start();
			        LibFontBoot.getInstance().start();
					ClassicEngineBoot.getInstance().start();
				}

				// Do the reporting thing...
				//
				kettleReportBuilder.createReport();
				kettleReportBuilder.render();
				
				Object[] outputRowData = RowDataUtil.allocateRowData(data.outputRowMeta.size());
				int outputIndex=0;
				outputRowData[outputIndex++] = targetFilename;
				
				// Pass along the data to the next steps...
				//
				putRow(data.outputRowMeta, outputRowData);
				
				// Add the target file to the result file list
				//
				ResultFile resultFile = new ResultFile(ResultFile.FILE_TYPE_GENERAL, targetFile, getTransMeta().getName(), toString());
				resultFile.setComment("This file was generated by the 'Auto Documentation Output' step");
				addResultFile(resultFile);
			} catch (Exception e) {
				throw new KettleException(BaseMessages.getString(PKG, "AutoDoc.Exception.UnableToRenderReport"), e);
			}
			
      setOutputDone();
      return false;
		}

		if (first) {
			first=false;
			
			data.outputRowMeta = getInputRowMeta().clone();
			meta.getFields(data.outputRowMeta, getStepname(), null, null, this, repository, metaStore);

			// Get the filename field index...
			//
			String filenameField = environmentSubstitute(meta.getFilenameField());
			data.fileNameFieldIndex = getInputRowMeta().indexOfValue(filenameField);
			if (data.fileNameFieldIndex <0) {
				throw new KettleException(BaseMessages.getString(PKG, "AutoDoc.Exception.FilenameFieldNotFound", filenameField));
			}

      // Get the file type field index...
      //
      String fileTypeField = environmentSubstitute(meta.getFileTypeField());
      data.fileTypeFieldIndex = getInputRowMeta().indexOfValue(fileTypeField);
      if (data.fileTypeFieldIndex <0) {
        throw new KettleException(BaseMessages.getString(PKG, "AutoDoc.Exception.FileTypeFieldNotFound", fileTypeField));
      }
      
      data.repository = getTrans().getRepository();
      if (data.repository!=null) {
        data.tree = data.repository.loadRepositoryDirectoryTree();
      }
      
      // Initialize the repository information handlers (images, metadata, loading, etc)
      //
      TransformationInformation.init(getTrans().getRepository());
      JobInformation.init(getTrans().getRepository());
		}
		
		// One more transformation or job to place in the documentation.
		//
		String fileName = getInputRowMeta().getString(row, data.fileNameFieldIndex);
		String fileType = getInputRowMeta().getString(row, data.fileTypeFieldIndex);

		RepositoryObjectType objectType;
    if ("Transformation".equalsIgnoreCase(fileType)) {
      objectType = RepositoryObjectType.TRANSFORMATION;
    } else if ("Job".equalsIgnoreCase(fileType)) {
      objectType = RepositoryObjectType.JOB;
    } else {
      throw new KettleException(BaseMessages.getString(PKG, "AutoDoc.Exception.UnknownFileTypeValue", fileType));
    }
		
		ReportSubjectLocation location = null;
		if (getTrans().getRepository()==null) {
		  switch(objectType) {
		  case TRANSFORMATION: location = new ReportSubjectLocation(fileName, null, null, objectType); break;
		  case JOB: location = new ReportSubjectLocation(fileName, null, null, objectType); break;
		  default: break;
		  }
		} else {
		  int lastSlashIndex = fileName.lastIndexOf(RepositoryDirectory.DIRECTORY_SEPARATOR);
		  if (lastSlashIndex<0) {
		    fileName=RepositoryDirectory.DIRECTORY_SEPARATOR+fileName;
		    lastSlashIndex=0;
		  }
		  
		  String directoryName = fileName.substring(0, lastSlashIndex+1);
		  String objectName = fileName.substring(lastSlashIndex+1);
		  
		  RepositoryDirectoryInterface directory = data.tree.findDirectory(directoryName);
		  if (directory==null) {
		    throw new KettleException(BaseMessages.getString(PKG, "AutoDoc.Exception.RepositoryDirectoryNotFound", directoryName));
		  }
		  
		  location = new ReportSubjectLocation(null, directory, objectName, objectType);
		}
    
		if (location==null) {
		  throw new KettleException(BaseMessages.getString(PKG, "AutoDoc.Exception.UnableToDetermineLocation", fileName, fileType));
		}
		
		if (meta.getOutputType()!=OutputType.METADATA) {
		  // Add the file location to the list for later processing in one output report
		  //
		  data.filenames.add(location);  
		} else {
		  // Load the metadata from the transformation / job...
		  // Output it in one row for each input row
		  //
		  Object[] outputRow = RowDataUtil.resizeArray(row, data.outputRowMeta.size());
		  int outputIndex = getInputRowMeta().size();
		  
		  List<AreaOwner> imageAreaList = null;
		  
		  switch(location.getObjectType()) {
		  case TRANSFORMATION:
  		  TransformationInformation ti = TransformationInformation.getInstance();
  		  TransMeta transMeta = ti.getTransMeta(location);
  		  imageAreaList = ti.getImageAreaList(location);
  		  
  		  // TransMeta
  		  outputRow[outputIndex++] = transMeta;
  		  break;
  		  
		  case JOB:
        JobInformation ji = JobInformation.getInstance();
        JobMeta jobMeta = ji.getJobMeta(location);
        imageAreaList = ji.getImageAreaList(location);
        
        // TransMeta
        outputRow[outputIndex++] = jobMeta;
        break;
      default:
        break;
		  }
		   
      // Name
      if (meta.isIncludingName()) {
        outputRow[outputIndex++] = KettleFileTableModel.getName(location);
      }

      // Description
      if (meta.isIncludingDescription()) {
        outputRow[outputIndex++] = KettleFileTableModel.getDescription(location);
      }

      // Extended Description
      if (meta.isIncludingExtendedDescription()) {
        outputRow[outputIndex++] = KettleFileTableModel.getExtendedDescription(location);
      }

      // created
      if (meta.isIncludingCreated()) {
        outputRow[outputIndex++] = KettleFileTableModel.getCreation(location);
      }

      // modified
      if (meta.isIncludingModified()) {
        outputRow[outputIndex++] = KettleFileTableModel.getModification(location);
      }

      // image
      if (meta.isIncludingImage()) {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        try {
          BufferedImage image = KettleFileTableModel.getImage(location);
          ImageIO.write(image, "png", outputStream);

          outputRow[outputIndex++] = outputStream.toByteArray();
        } catch (Exception e) {
          throw new KettleException("Unable to serialize image to PNG", e);
        } finally {
          try {
            outputStream.close();
          } catch (IOException e) {
            throw new KettleException("Unable to serialize image to PNG", e);
          }
        }
      }

      if (meta.isIncludingLoggingConfiguration()) {
        outputRow[outputIndex++] = KettleFileTableModel.getLogging(location);
      }

      if (meta.isIncludingLastExecutionResult()) {
        outputRow[outputIndex++] = KettleFileTableModel.getLogging(location);
      }
      
      if (meta.isIncludingImageAreaList()) {
        outputRow[outputIndex++] = imageAreaList;
      }
      
      putRow(data.outputRowMeta, outputRow);
		}

		return true;
	}
}

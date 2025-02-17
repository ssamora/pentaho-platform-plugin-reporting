/*! ******************************************************************************
 *
 * Pentaho
 *
 * Copyright (C) 2024 by Hitachi Vantara, LLC : http://www.pentaho.com
 *
 * Use of this software is governed by the Business Source License included
 * in the LICENSE.TXT file.
 *
 * Change Date: 2029-07-20
 ******************************************************************************/



package org.pentaho.reporting.platform.plugin.async;
import java.io.IOException;
import java.io.OutputStream;
import java.util.concurrent.Callable;

import org.apache.commons.io.IOUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.pentaho.platform.api.engine.IPentahoSession;
import org.pentaho.platform.engine.core.audit.MessageTypes;
import org.pentaho.platform.engine.core.system.PentahoSessionHolder;
import org.pentaho.platform.engine.security.SecurityHelper;
import org.pentaho.reporting.engine.classic.core.MasterReport;
import org.pentaho.reporting.engine.classic.core.event.async.AsyncExecutionStatus;
import org.pentaho.reporting.engine.classic.core.event.async.AsyncReportStatusListener;
import org.pentaho.reporting.engine.classic.core.event.async.IAsyncReportState;
import org.pentaho.reporting.engine.classic.core.event.async.ReportListenerThreadHolder;
import org.pentaho.reporting.libraries.resourceloader.ResourceException;
import org.pentaho.reporting.platform.plugin.AuditWrapper;
import org.pentaho.reporting.platform.plugin.ReportCreator;
import org.pentaho.reporting.platform.plugin.SimpleReportingComponent;
import org.pentaho.reporting.platform.plugin.staging.AsyncJobFileStagingHandler;
import org.pentaho.reporting.platform.plugin.staging.IFixedSizeStreamingContent;

public class PentahoAsyncReportExecution extends AbstractAsyncReportExecution<IAsyncReportState> {

  private static final Log log = LogFactory.getLog( PentahoAsyncReportExecution.class );

  public PentahoAsyncReportExecution( String url,
                                      SimpleReportingComponent reportComponent,
                                      AsyncJobFileStagingHandler handler,
                                      IPentahoSession safeSession,
                                      String auditId,
                                      AuditWrapper audit ) {
    super( url, reportComponent, handler, safeSession, auditId, audit );
  }


  PentahoAsyncReportExecution( final PentahoAsyncReportExecution old, final AsyncJobFileStagingHandler handler ) {
    super( old.url, old.reportComponent, handler, old.safeSession, old.auditId, old.getAudit() );
    old.reportComponent.setOutputStream( handler.getStagingOutputStream() );
    final MasterReport report = getReport( old );
    if ( report != null ) {
      old.reportComponent.setReport( report );
    }
  }

  private MasterReport getReport( PentahoAsyncReportExecution old ) {
    final String path = old.getState().getPath();
    try {
      return ReportCreator.createReportByName( path );
    } catch ( ResourceException | IOException e ) {
      log.error( "No report was found on provided path", e );
    }
    return null;
  }

  /**
   * Generate report and return input stream to a generated report from server.
   * <p>
   * Pay attention - it is important to set proper status during execution. In case 'fail' or 'complete' status not set
   * - status remains 'working' and executor unable to determine that actual execution has ended.
   *
   * @return input stream for client
   * @throws Exception
   */
  @Override public IFixedSizeStreamingContent call() throws Exception {
    mdcUtil.setContextMap();
    final AsyncReportStatusListener listener = getListener();
    if ( listener == null ) {
      throw new NullPointerException( "No listener for async report execution: " + url );
    }

    return SecurityHelper.getInstance().runAsUser( safeSession.getName(), new Callable<IFixedSizeStreamingContent>() {
      @Override public IFixedSizeStreamingContent call() throws Exception {
        try {
          listener.setStatus( AsyncExecutionStatus.WORKING );

          PentahoSessionHolder.setSession( safeSession );
          ReportListenerThreadHolder.setListener( listener );
          ReportListenerThreadHolder.setRequestId( auditId );

          final long start = System.currentTimeMillis();

          getAudit().audit( safeSession.getId(), safeSession.getName(), url, getClass().getName(), getClass().getName(),
            MessageTypes.INSTANCE_START, auditId, "", 0, null );

          if ( reportComponent.execute() ) {

            final long end = System.currentTimeMillis();

            getAudit()
              .audit( safeSession.getId(), safeSession.getName(), url, getClass().getName(), getClass().getName(),
                MessageTypes.INSTANCE_END, auditId, "", ( (float) ( end - start ) / 1000 ), null );

            final IFixedSizeStreamingContent stagingContent = handler.getStagingContent();

            listener.setStatus( AsyncExecutionStatus.FINISHED );

            return stagingContent;
          }

          // in case execute just returns false without an exception.
          fail();
          return NULL;
        } catch ( final Throwable ee ) {
          // it is bad practice to catch throwable.
          // but we has to to set proper execution status in any case.
          // Example: NoSuchMethodError (instance of Error) in case of usage of
          // uncompilable jar versions.
          // We have to avoid to hang on working status.
          log.error( "fail to execute report in async mode: " + ee );

          if ( ee.getMessage() != null ) {
            String errorMessage = "";
            Throwable throwable = ee;

            while ( throwable != null ) {
              errorMessage += throwable.getMessage() + ".\n";
              throwable = throwable.getCause();
            }

            listener.setErrorMessage( errorMessage );
          }
          fail();
          return NULL;
        } finally {
          // in case report processor not going to close it
          OutputStream out = handler.getStagingOutputStream();
          IOUtils.closeQuietly( out );

          ReportListenerThreadHolder.clear();
          PentahoSessionHolder.removeSession();
        }
      }
    } );
  }

  @Override public String toString() {
    return "PentahoAsyncReportExecution{" + "url='" + url + '\'' + ", instanceId='" + auditId + '\'' + ", listener="
      + getListener() + '}';
  }

  @Override public IAsyncReportState getState() {
    final AsyncReportStatusListener listener = getListener();
    if ( listener == null ) {
      throw new IllegalStateException( "Cannot query state until job is added to the executor." );
    }
    return listener.getState();
  }
}

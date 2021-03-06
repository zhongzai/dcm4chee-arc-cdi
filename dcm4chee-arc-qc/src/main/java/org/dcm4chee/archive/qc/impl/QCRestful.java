/* ***** BEGIN LICENSE BLOCK *****
 * Version: MPL 1.1/GPL 2.0/LGPL 2.1
 *
 * The contents of this file are subject to the Mozilla Public License Version
 * 1.1 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 * http://www.mozilla.org/MPL/
 *
 * Software distributed under the License is distributed on an "AS IS" basis,
 * WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
 * for the specific language governing rights and limitations under the
 * License.
 *
 * The Original Code is part of dcm4che, an implementation of DICOM(TM) in
 * Java(TM), hosted at https://github.com/gunterze/dcm4che.
 *
 * The Initial Developer of the Original Code is
 * Agfa Healthcare.
 * Portions created by the Initial Developer are Copyright (C) 2013
 * the Initial Developer. All Rights Reserved.
 *
 * Contributor(s):
 * See @authors listed below
 *
 * Alternatively, the contents of this file may be used under the terms of
 * either the GNU General Public License Version 2 or later (the "GPL"), or
 * the GNU Lesser General Public License Version 2.1 or later (the "LGPL"),
 * in which case the provisions of the GPL or the LGPL are applicable instead
 * of those above. If you wish to allow use of your version of this file only
 * under the terms of either the GPL or the LGPL, and not to allow others to
 * use your version of this file under the terms of the MPL, indicate your
 * decision by deleting the provisions above and replace them with the notice
 * and other provisions required by the GPL or the LGPL. If you do not delete
 * the provisions above, a recipient may use your version of this file under
 * the terms of any one of the MPL, the GPL or the LGPL.
 *
 * ***** END LICENSE BLOCK ***** */
package org.dcm4chee.archive.qc.impl;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;

import javax.inject.Inject;
import javax.json.Json;
import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.Response;

import javax.ws.rs.core.UriInfo;
import javax.ws.rs.core.Response.Status;

import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.Code;
import org.dcm4che3.data.ElementDictionary;
import org.dcm4che3.data.IDWithIssuer;
import org.dcm4che3.data.Issuer;
import org.dcm4che3.json.JSONReader;
import org.dcm4che3.json.JSONReader.Callback;
import org.dcm4che3.net.ApplicationEntity;
import org.dcm4che3.net.Device;
import org.dcm4che3.util.TagUtils;
import org.dcm4chee.archive.conf.ArchiveAEExtension;
import org.dcm4chee.archive.conf.ArchiveDeviceExtension;
import org.dcm4chee.archive.qc.IssuerObject;
import org.dcm4chee.archive.qc.PatientCommands;
import org.dcm4chee.archive.qc.QCBean;
import org.dcm4chee.archive.qc.QCEvent;
import org.dcm4chee.archive.qc.QCObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The Class QCRestful provides a rest interface for QC.
 * 
 * @author Hesham Elbadawi <bsdreko@gmail.com>
 */

@Path("/qc/{AETitle}")
public class QCRestful {

    private static final Logger LOG = LoggerFactory.getLogger(QCRestful.class);

    private static String RSP;

    @Inject
    private Device device;

    @Inject
    private QCBean qcManager;

    private String aeTitle;

    private ArchiveAEExtension arcAEExt;

    /**
     * Sets the AE title.
     * 
     * @param aet
     *            the new AE title
     */
    @PathParam("AETitle")
    public void setAETitle(String aet) {
        this.aeTitle=aet;
        ApplicationEntity ae = device.getApplicationEntity(aet);
        
        if (ae == null || !ae.isInstalled()
                || (arcAEExt = ae.getAEExtension(ArchiveAEExtension.class)) == null) {
            throw new WebApplicationException(Response.Status.SERVICE_UNAVAILABLE);
        }
}

    /**
     * Performs a QC operation based on the provided object.
     * 
     * @param object
     *            the QCObject generated by the rest call
     * @return the response
     */
    @POST
    @Consumes("application/json")
    public Response performQC(QCObject object) {
        
        QCEvent event = null;
        Code code = (object.getQcRejectionCode().getCodeValue()!=null?initializeCode(object):null);
        IDWithIssuer pid = (object.getPid().getId()!=null?initializeIDWithIssuer(object):null);
        
        try{
        switch (object.getOperation().toLowerCase()) {
        case "update":
            ArchiveDeviceExtension arcDevExt = device.getDeviceExtension(ArchiveDeviceExtension.class);

            if(object.getUpdateScope() == null) {
                LOG.error("Unable to decide update Scope for QC update");
                throw new WebApplicationException( Response.status(Status.CONFLICT)
                        .entity("Unrecognized update scope")
                        .build());
            }
            else {
                //here merge provided data with pid, study uid, series uid , instance uid
                event = qcManager.updateDicomObject(arcDevExt, object.getUpdateScope(), object.getUpdateData());
            }
            
            break;
            
        case "merge":
            
            event = qcManager.mergeStudies(
                    object.getMoveSOPUIDS(), object.getTargetStudyUID(), 
                    object.getTargetStudyData(), object.getTargetSeriesData(), 
                    code);
            break;
            
        case "split":
            event = qcManager.split(Arrays.asList(object.getMoveSOPUIDS()), pid,
                    object.getTargetStudyUID(), object.getTargetStudyData(), 
                    object.getTargetSeriesData(),  code);
            break;
            
        case "segment":
            
            event = qcManager.segment(
                    Arrays.asList(object.getMoveSOPUIDS()),
                    Arrays.asList(object.getCloneSOPUIDs()), pid,
                    object.getTargetStudyUID(), object.getTargetStudyData(), 
                    object.getTargetSeriesData(),  code);
            break;
            
        case "reject":
            
            event = qcManager.reject(
                    object.getRestoreOrRejectUIDs(), code);
            break;
            
        case "restore":
            
            event = qcManager.restore( 
                    object.getRestoreOrRejectUIDs());
            break;
        default:
            return Response.status(Response.Status.CONFLICT).entity("Unable to decide operation").build();
        }
        }
        catch(Exception e) {
            LOG.error("{} : Error in performing QC - Restful interface", e);
            throw new WebApplicationException( Response.status(Status.CONFLICT)
                    .entity(e.getMessage())
                    .build());
        }
        qcManager.notify(event);
        String strEvent = event.toString();
        return Response.ok("Successfully performed operation "+object.getOperation() + " Operation resulted in the following event :\n" +strEvent).build();
    }

    /**
     * Patient operation.
     * Calls the patient service to perform operations.
     * 
     * @param uriInfo
     *            the uri info
     * @param in
     *            the in
     * @param patientOperation
     *            the patient operation
     * @return the response
     */
    @POST
    @Path("patients/{PatientOperation}")
    @Consumes("application/json")
    public Response patientOperation(@Context UriInfo uriInfo, InputStream in,
            @PathParam("PatientOperation") String patientOperation) {
        PatientCommands command = patientOperation.equalsIgnoreCase("merge")? PatientCommands.PATIENT_MERGE
                : patientOperation.equalsIgnoreCase("link")? PatientCommands.PATIENT_LINK
                        : patientOperation.equalsIgnoreCase("unlink")? PatientCommands.PATIENT_UNLINK
                                : patientOperation.equalsIgnoreCase("updateids")? PatientCommands.PATIENT_UPDATE_ID
                                : null;
        
        if (command == null)
            throw new WebApplicationException( Response.status(Status.CONFLICT)
                    .entity("Unable to decide patient command - supported commands {merge, link, unlink, updateids}")
                    .build()
                    );
        ArrayList<Attributes> attrs = null;
        try {
            attrs = parseJSONAttributesToList(in);
            if (LOG.isDebugEnabled()) {
                LOG.debug("Received Attributes for patient operation - "+patientOperation);
                for(int i=0; i< attrs.size();i++){
                    LOG.debug(i%2==0 ? "Source data[{}]: ":"Target data[{}]: ",(i/2)+1);
                    LOG.debug(attrs.get(i).toString(0, attrs.get(i).size()));
                }
            }
        }
        catch(Exception e)
        {
            throw new WebApplicationException( Response.status(Status.CONFLICT)
                    .entity("Unable to process patient operation request data")
                    .build()
                    );
        }

        return aggregatePatientOpResponse(attrs,device.getApplicationEntity(aeTitle),command, patientOperation);
    }

    /**
     * Delete study.
     * 
     * @param studyInstanceUID
     *            the study instance uid
     * @return the response
     */
    @DELETE
    @Path("delete/studies/{StudyInstanceUID}")
    public Response deleteStudy(
            @PathParam("StudyInstanceUID") String studyInstanceUID) {
        RSP = "Deleted Study with UID = ";
        QCEvent event = null;
        try{
            event = qcManager.deleteStudy(studyInstanceUID);
        }
        catch (Exception e)
        {
            RSP = "Failed to delete study with UID = "+studyInstanceUID;
            return Response.status(Status.CONFLICT).entity(RSP).build();
        }
        RSP += studyInstanceUID;
        qcManager.notify(event);
        return Response.ok(RSP).build();

    }

    /**
     * Delete series.
     * 
     * @param studyInstanceUID
     *            the study instance uid
     * @param seriesInstanceUID
     *            the series instance uid
     * @return the response
     */
    @DELETE
    @Path("delete/studies/{StudyInstanceUID}/series/{SeriesInstanceUID}")
    public Response deleteSeries(
            @PathParam("StudyInstanceUID") String studyInstanceUID,
            @PathParam("SeriesInstanceUID") String seriesInstanceUID) {
        RSP = "Deleted Series with UID = ";
        QCEvent event = null;
        try {
            event = qcManager.deleteSeries(seriesInstanceUID);
        }
        catch(Exception e)
        {
            RSP = "Failed to delete series with UID = "+seriesInstanceUID;
            return Response.status(Status.CONFLICT).entity(RSP).build();
        }
        RSP+=seriesInstanceUID;
        qcManager.notify(event);
        return Response.ok(RSP).build();
    }

    /**
     * Delete instance.
     * 
     * @param studyInstanceUID
     *            the study instance uid
     * @param seriesInstanceUID
     *            the series instance uid
     * @param sopInstanceUID
     *            the sop instance uid
     * @return the response
     */
    @DELETE
    @Path("delete/studies/{StudyInstanceUID}/series/{SeriesInstanceUID}/instances/{SOPInstanceUID}")
    public Response deleteInstance(
            @PathParam("StudyInstanceUID") String studyInstanceUID,
            @PathParam("SeriesInstanceUID") String seriesInstanceUID,
            @PathParam("SOPInstanceUID") String sopInstanceUID) {
        RSP = "Deleted Instance with UID = ";
        QCEvent event = null;
        try{
            event = qcManager.deleteInstance(sopInstanceUID);
        }
        catch(Exception e)
        {
            RSP = "Failed to delete Instance with UID = "+sopInstanceUID;
            return Response.status(Status.CONFLICT).entity(RSP).build();
        } 
        RSP+=sopInstanceUID;
        qcManager.notify(event);
        return Response.ok(RSP).build();
    }

    /**
     * Delete series if empty.
     * 
     * @param studyInstanceUID
     *            the study instance uid
     * @param seriesInstanceUID
     *            the series instance uid
     * @return the response
     */
    @DELETE
    @Path("purge/studies/{StudyInstanceUID}/series/{SeriesInstanceUID}")
    public Response deleteSeriesIfEmpty(
            @PathParam("StudyInstanceUID") String studyInstanceUID,
            @PathParam("SeriesInstanceUID") String seriesInstanceUID) {
        RSP = "Series with UID = "
                + seriesInstanceUID
                + " was empty and Deleted = "
                + qcManager.deleteSeriesIfEmpty(seriesInstanceUID,
                        studyInstanceUID);

        return Response.ok(RSP).build();
    }

    /**
     * Delete study if empty.
     * 
     * @param studyInstanceUID
     *            the study instance uid
     * @return the response
     */
    @DELETE
    @Path("purge/studies/{StudyInstanceUID}")
    public Response deleteStudyIfEmpty(
            @PathParam("StudyInstanceUID") String studyInstanceUID) {
        RSP = "Study with UID = " + studyInstanceUID
                + " was empty and Deleted = "
                + qcManager.deleteStudyIfEmpty(studyInstanceUID);

        return Response.ok(RSP).build();
    }

    /**
     * Aggregate patient operation response.
     * 
     * @param attrs
     *            the attributes used for each patient operation
     * @param applicationEntity
     *            the application entity
     * @param command
     *            the command
     * @param patientOperation
     *            the patient operation
     * @return the response
     */
    private Response aggregatePatientOpResponse(ArrayList<Attributes> attrs,
            ApplicationEntity applicationEntity, PatientCommands command, String patientOperation) {
        ArrayList<Boolean> listRSP = new ArrayList<Boolean>();
        for(int i=0;i<attrs.size();i++)
        listRSP.add(qcManager.patientOperation(attrs.get(i), attrs.get(++i),
                arcAEExt, command));
        int trueCount=0;
        
        for(boolean rsp: listRSP)
        if(rsp)
        trueCount++;
        return trueCount==listRSP.size()?
                Response.status(Status.OK).entity
                ("Patient operation successful - "+patientOperation).build():
                trueCount==0?Response.status(Status.CONFLICT).entity
                ("Error - Unable to perform patient operation - "+patientOperation).build():
                Response.status(Status.CONFLICT).entity
                ("Warning - Unable to perform some operations - "+patientOperation).build();
    }

    /**
     * Parses the JSON attributes to list of attributes.
     * Used by the patient operations.
     * 
     * @param in
     *            the input stream
     * @return the array list
     * @throws IOException
     *             Signals that an I/O exception has occurred.
     */
    private ArrayList<Attributes> parseJSONAttributesToList(InputStream in) throws IOException {

        JSONReader reader = new JSONReader(
                Json.createParser(new InputStreamReader(in, "UTF-8")));
        final ArrayList<Attributes> attributesList = new ArrayList<Attributes>();
        
        reader.readDatasets(new Callback() {
            
            @Override
            public void onDataset(Attributes fmi, Attributes dataset) {
                attributesList.add(dataset);
            }
        });
        ElementDictionary dict = ElementDictionary
                .getStandardElementDictionary();

        for (int i = 0; i < attributesList.size(); i++){
            HashMap<String, String> tmpQMap = new HashMap<String, String>(); 
        for (int j = 0; j < attributesList.get(i).tags().length; j++) {
            Attributes ds = attributesList.get(i);
            if (TagUtils.isPrivateTag(ds.tags()[j])) {
                dict = ElementDictionary.getElementDictionary(ds
                        .getPrivateCreator(ds.tags()[j]));
            } else {
                dict = ElementDictionary.getStandardElementDictionary();
                tmpQMap.put(dict.keywordOf(ds.tags()[j]),
                        ds.getString(ds.tags()[j]));
            }
        }
        
        }
        if (attributesList.size()%2!=0)
            throw new WebApplicationException(new Exception(
                    "Unable to decide request data"), Response.Status.BAD_REQUEST);

        return attributesList;
    }

    /**
     * Initialize code.
     * 
     * @param object
     *            the QCobject with a rejection code
     * @return the rejection code
     */
    private Code initializeCode(QCObject object) {
        return new Code(object.getQcRejectionCode().getCodeValue(),
                object.getQcRejectionCode().getCodingSchemeDesignator(),
                object.getQcRejectionCode().getCodingSchemeVersion(),
                object.getQcRejectionCode().getCodeMeaning());
    }

    /**
     * Initialize id with issuer.
     * 
     * @param object
     *            the qc object
     * @return the ID with issuer
     */
    private IDWithIssuer initializeIDWithIssuer(QCObject object) {
        IssuerObject issuerObj = object.getPid().getIssuer();
        return new IDWithIssuer(object.getPid().getId(), 
                new Issuer(issuerObj.getLocalNamespaceEntityID(),
                        issuerObj.getUniversalEntityID(),
                        issuerObj.getUniversalEntityIDType()));
    }
}

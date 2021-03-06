/*
 *   Copyright 2021 Oleg Zharkov
 *
 *   Licensed under the Apache License, Version 2.0 (the "License").
 *   You may not use this file except in compliance with the License.
 *   A copy of the License is located at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *   or in the "license" file accompanying this file. This file is distributed
 *   on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 *   express or implied. See the License for the specific language governing
 *   permissions and limitations under the License.
 */

package org.alertflex.controller;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.zip.GZIPOutputStream;
import javax.jms.BytesMessage;
import javax.jms.Connection;
import javax.jms.DeliveryMode;
import javax.jms.Destination;
import javax.jms.JMSException;
import javax.jms.MessageConsumer;
import javax.jms.MessageProducer;
import javax.jms.Session;
import javax.jms.TextMessage;
import javax.persistence.PersistenceException;
import org.alertflex.common.ProjectRepository;
import org.alertflex.entity.Agent;
import org.alertflex.entity.AgentPackages;
import org.alertflex.entity.AgentProcesses;
import org.alertflex.entity.Alert;
import org.alertflex.entity.NodeAlerts;
import org.alertflex.entity.NodeMonitor;
import org.alertflex.entity.NetStat;
import org.alertflex.entity.AgentSca;
import org.alertflex.entity.AgentVul;
import org.alertflex.entity.AgentProcesses;
import org.alertflex.entity.AgentPackages;
import org.alertflex.entity.AlertPriority;
import org.alertflex.entity.Container;
import org.alertflex.entity.HomeNetwork;
import org.alertflex.entity.Node;
import org.alertflex.entity.Project;
import org.apache.activemq.ActiveMQConnectionFactory;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class StatsManagement {
    
    private static final long TIMEOUT = 1 * 1000;

    private static final Logger logger = LoggerFactory.getLogger(StatsManagement.class);

    private InfoMessageBean eventBean;
    
    Project project;
    ProjectRepository pr;

    public StatsManagement(InfoMessageBean eb) {
        this.eventBean = eb;
        this.project = eventBean.getProject();
        this.pr = new ProjectRepository(project);
    }

    public void EvaluateStats(String stats) throws ParseException {

        try {

            JSONObject obj = new JSONObject(stats);
            JSONArray arr = obj.getJSONArray("stats");

            for (int i = 0; i < arr.length(); i++) {

                persistStats(arr.getJSONObject(i));

                //logger.error(arr.getJSONObject(i).toString());
            }

        } catch (JSONException e) {

            logger.error("alertflex_ctrl_exception", e);
            logger.error(stats);
        }
    }

    public void persistStats(JSONObject obj) throws ParseException {

        String stat = obj.toString();

        try {
            
            JSONObject data;
            JSONArray arr;
            
            String ref = eventBean.getRefId();
            String node = eventBean.getNode();
            
            String agent;
            
            SimpleDateFormat formatter;
            Date date = new Date();

            String stats_type = obj.getString("type");
            

            switch (stats_type) {

                case "agents_list": 

                    boolean filtersFlag = false;

                    arr = obj.getJSONArray("data");
                    
                    formatter = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

                    for (int i = 0; i < arr.length(); i++) {

                        date = formatter.parse(arr.getJSONObject(i).getString("time_of_survey"));

                        agent = arr.getJSONObject(i).getString("name");
                        String id = arr.getJSONObject(i).getString("id");
                        String status = arr.getJSONObject(i).getString("status");
                        String ip = arr.getJSONObject(i).getString("ip");
                        String dateAdd = arr.getJSONObject(i).getString("date_add");
                        String version = arr.getJSONObject(i).getString("version");
                        String managerHost = arr.getJSONObject(i).getString("manager_host");
                        String osPlatform = arr.getJSONObject(i).getString("os_platform");
                        String osVersion = arr.getJSONObject(i).getString("os_version");
                        String osName = arr.getJSONObject(i).getString("os_name");

                        Agent agExisting = eventBean.getAgentFacade().findAgentByName(ref, node, agent);

                        if (agExisting == null) {
                            
                            filtersFlag = true;

                            Agent a = new Agent();

                            a.setRefId(ref);
                            a.setNodeId(node);
                            a.setDateUpdate(date);
                            a.setStatus(status);
                            a.setAgentId(id);
                            a.setAgentKey("indef");
                            a.setName(agent);
                            a.setIp(ip);
                            a.setDateAdd(dateAdd);
                            a.setVersion(version);
                            a.setManager(managerHost);
                            a.setOsPlatform(osPlatform);
                            a.setOsVersion(osVersion);
                            a.setOsName(osName);

                            eventBean.getAgentFacade().create(a);

                            createNewAgentAlert(a);

                        } else {

                            agExisting.setDateUpdate(date);
                            agExisting.setStatus(status);
                            agExisting.setAgentId(id);
                            agExisting.setIp(ip);
                            agExisting.setDateAdd(dateAdd);
                            agExisting.setVersion(version);
                            agExisting.setManager(managerHost);
                            agExisting.setOsPlatform(osPlatform);
                            agExisting.setOsVersion(osVersion);
                            agExisting.setOsName(osName);

                            eventBean.getAgentFacade().edit(agExisting);
                        }
                    }
                    
                    if (filtersFlag) {
                        updateFilters(ref, node);
                    }

                    break;
                
                case "containers_list": 

                    arr = obj.getJSONArray("data");
                    
                    String probe = obj.getString("probe");
                    
                    formatter = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

                    for (int i = 0; i < arr.length(); i++) {

                        String containerId = arr.getJSONObject(i).getString("Id");
                        String imageName = arr.getJSONObject(i).getString("Image");
                        String imageId = arr.getJSONObject(i).getString("ImageID");
                        String command = arr.getJSONObject(i).getString("Command");
                        Long created = arr.getJSONObject(i).getLong("Created");
                        String state = arr.getJSONObject(i).getString("State");
                        String status = arr.getJSONObject(i).getString("Status");
                        
                        Container contExisting = eventBean.getContainerFacade().findByName(ref, node, probe, containerId);

                        if (contExisting == null) {

                            Container c = new Container();

                            c.setRefId(ref);
                            c.setNodeId(node);
                            c.setProbe(probe);
                            c.setContainerId(containerId);
                            c.setImageName(imageName);
                            c.setImageId(imageId);
                            c.setCommand(command);
                            c.setState(state);
                            c.setStatus(status);
                            c.setReportAdded(new Date(created));
                            c.setReportUpdated(new Date());

                            eventBean.getContainerFacade().create(c);

                            createNewContainerAlert(c);

                        } else {
                            contExisting.setReportUpdated(new Date());
                            eventBean.getContainerFacade().edit(contExisting);
                        }
                    }

                    break;
                    
                case "packages":

                    agent = obj.getString("agent");

                    data = obj.getJSONObject("data");

                    arr = data.getJSONArray("affected_items");
                    
                    formatter = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'");

                    for (int i = 0; i < arr.length(); i++) {

                        JSONObject scan = arr.getJSONObject(i).getJSONObject("scan");
                        Date time = formatter.parse(scan.getString("time"));

                        long size = 0;
                        if (arr.getJSONObject(i).has("size")) {
                            size = arr.getJSONObject(i).getLong("size");
                        }

                        String architecture = "";
                        if (arr.getJSONObject(i).has("architecture")) {
                            architecture = arr.getJSONObject(i).getString("architecture");
                        }

                        String priority = "";
                        if (arr.getJSONObject(i).has("priority")) {
                            priority = arr.getJSONObject(i).getString("priority");
                        }

                        String version = "";
                        if (arr.getJSONObject(i).has("version")) {
                            version = arr.getJSONObject(i).getString("version");
                        }

                        String vendor = "";
                        if (arr.getJSONObject(i).has("vendor")) {
                            vendor = arr.getJSONObject(i).getString("vendor");
                        }

                        String format = "";
                        if (arr.getJSONObject(i).has("format")) {
                            format = arr.getJSONObject(i).getString("format");
                        }

                        String section = "";
                        if (arr.getJSONObject(i).has("section")) {
                            section = arr.getJSONObject(i).getString("section");
                        }

                        String name = "";
                        if (arr.getJSONObject(i).has("name")) {
                            name = arr.getJSONObject(i).getString("name");
                        }

                        String description = "";
                        if (arr.getJSONObject(i).has("description")) {
                            description = arr.getJSONObject(i).getString("description");
                        }

                        AgentPackages pExisting = eventBean.getAgentPackagesFacade().findPackage(ref, node, agent, name, version);

                        if (pExisting == null) {

                            AgentPackages p = new AgentPackages();

                            p.setRefId(ref);
                            p.setNodeId(node);
                            p.setAgent(agent);
                            p.setPackageSize(size);
                            p.setArchitecture(architecture);
                            p.setPriority(priority);
                            p.setVersion(version);
                            p.setVendor(vendor);
                            p.setPackageFormat(format);
                            p.setPackageSection(section);
                            p.setName(name);
                            p.setDescription(description);
                            p.setTimeScan(time);
                            p.setDateAdd(date);
                            p.setDateUpdate(date);

                            eventBean.getAgentPackagesFacade().create(p);

                            // createNewPackageAlert(p); Wazuh care
                        } else {

                            pExisting.setPackageSize(size);
                            pExisting.setArchitecture(architecture);
                            pExisting.setPriority(priority);
                            pExisting.setVersion(version);
                            pExisting.setVendor(vendor);
                            pExisting.setPackageFormat(format);
                            pExisting.setPackageSection(section);
                            pExisting.setName(name);
                            pExisting.setTimeScan(time);
                            pExisting.setDateUpdate(date);

                            eventBean.getAgentPackagesFacade().edit(pExisting);
                        }
                    }

                    break;
                
                case "processes": 

                    agent = obj.getString("agent");

                    data = obj.getJSONObject("data");

                    arr = data.getJSONArray("affected_items");
                    
                    formatter = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'");

                    for (int i = 0; i < arr.length(); i++) {

                        JSONObject scan = arr.getJSONObject(i).getJSONObject("scan");
                        Date time = formatter.parse(scan.getString("time"));

                        int utime = 0;
                        if (arr.getJSONObject(i).has("utime")) {
                            utime = arr.getJSONObject(i).getInt("utime");
                        }

                        String state = "";
                        if (arr.getJSONObject(i).has("state")) {
                            state = arr.getJSONObject(i).getString("state");
                        }

                        int priority = 0;
                        if (arr.getJSONObject(i).has("priority")) {
                            priority = arr.getJSONObject(i).getInt("priority");
                        }

                        String name = "";
                        if (arr.getJSONObject(i).has("name")) {
                            name = arr.getJSONObject(i).getString("name");
                        }

                        int share = 0;
                        if (arr.getJSONObject(i).has("share")) {
                            share = arr.getJSONObject(i).getInt("share");
                        }

                        String suser = "";
                        if (arr.getJSONObject(i).has("suser")) {
                            suser = arr.getJSONObject(i).getString("suser");
                        }

                        String egroup = "";
                        if (arr.getJSONObject(i).has("egroup")) {
                            egroup = arr.getJSONObject(i).getString("egroup");
                        }

                        int nlwp = 0;
                        if (arr.getJSONObject(i).has("nlwp")) {
                            nlwp = arr.getJSONObject(i).getInt("nlwp");
                        }

                        int nice = 0;
                        if (arr.getJSONObject(i).has("nice")) {
                            nice = arr.getJSONObject(i).getInt("nice");
                        }

                        String sgroup = "";
                        if (arr.getJSONObject(i).has("sgroup")) {
                            sgroup = arr.getJSONObject(i).getString("sgroup");
                        }

                        int ppid = 0;
                        if (arr.getJSONObject(i).has("ppid")) {
                            ppid = arr.getJSONObject(i).getInt("ppid");
                        }

                        int processor = 0;
                        if (arr.getJSONObject(i).has("processor")) {
                            processor = arr.getJSONObject(i).getInt("processor");
                        }

                        String pid = "";
                        if (arr.getJSONObject(i).has("pid")) {
                            pid = arr.getJSONObject(i).getString("pid");
                        }

                        String euser = "";
                        if (arr.getJSONObject(i).has("euser")) {
                            euser = arr.getJSONObject(i).getString("euser");
                        }

                        String ruser = "";
                        if (arr.getJSONObject(i).has("ruser")) {
                            ruser = arr.getJSONObject(i).getString("ruser");
                        }

                        int session = 0;
                        if (arr.getJSONObject(i).has("session")) {
                            session = arr.getJSONObject(i).getInt("session");
                        }

                        int pgrp = 0;
                        if (arr.getJSONObject(i).has("pgrp")) {
                            pgrp = arr.getJSONObject(i).getInt("pgrp");
                        }

                        int stime = 0;
                        if (arr.getJSONObject(i).has("stime")) {
                            stime = arr.getJSONObject(i).getInt("stime");
                        }

                        long vm_size = 0;
                        if (arr.getJSONObject(i).has("vm_size")) {
                            vm_size = arr.getJSONObject(i).getLong("vm_size");
                        }

                        int tgid = 0;
                        if (arr.getJSONObject(i).has("tgid")) {
                            tgid = arr.getJSONObject(i).getInt("tgid");
                        }

                        int tty = 0;
                        if (arr.getJSONObject(i).has("tty")) {
                            tty = arr.getJSONObject(i).getInt("tty");
                        }

                        String rgroup = "";
                        if (arr.getJSONObject(i).has("rgroup")) {
                            rgroup = arr.getJSONObject(i).getString("rgroup");
                        }

                        long size = 0;
                        if (arr.getJSONObject(i).has("size")) {
                            size = arr.getJSONObject(i).getLong("size");
                        }

                        int resident = 0;
                        if (arr.getJSONObject(i).has("resident")) {
                            resident = arr.getJSONObject(i).getInt("resident");
                        }

                        String fgroup = "";
                        if (arr.getJSONObject(i).has("fgroup")) {
                            fgroup = arr.getJSONObject(i).getString("fgroup");
                        }

                        int startTime = 0;
                        if (arr.getJSONObject(i).has("start_time")) {
                            startTime = arr.getJSONObject(i).getInt("start_time");
                        }

                        String cmd = "";
                        if (arr.getJSONObject(i).has("cmd")) {
                            cmd = arr.getJSONObject(i).getString("cmd");
                        }

                        AgentProcesses pExisting = eventBean.getAgentProcessesFacade().findProcess(ref, node, agent, name, pid);

                        if (pExisting == null) {

                            AgentProcesses p = new AgentProcesses();

                            p.setRefId(ref);
                            p.setNodeId(node);
                            p.setAgent(agent);
                            p.setUtime(utime);
                            p.setProcessState(state);
                            p.setPriority(priority);
                            p.setName(name);
                            p.setProcessShare(share);
                            p.setSuser(suser);
                            p.setEgroup(egroup);
                            p.setNlwp(nlwp);
                            p.setNice(nice);
                            p.setSgroup(sgroup);
                            p.setPpid(ppid);
                            p.setProcessor(processor);
                            p.setPid(pid);
                            p.setEuser(euser);
                            p.setRuser(ruser);
                            p.setProcessSession(session);
                            p.setPgrp(pgrp);
                            p.setStime(stime);
                            p.setVmSize(vm_size);
                            p.setTgid(tgid);
                            p.setTty(tty);
                            p.setRgroup(rgroup);
                            p.setResident(resident);
                            p.setFgroup(fgroup);
                            p.setStartTime(startTime);
                            p.setCmd(cmd);

                            p.setTimeScan(time);
                            p.setDateAdd(date);
                            p.setDateUpdate(date);

                            eventBean.getAgentProcessesFacade().create(p);

                            // createNewProcessAlert(p); Wazuh care
                        } else {

                            pExisting.setProcessState(state);
                            pExisting.setPriority(priority);
                            pExisting.setProcessShare(share);
                            pExisting.setSuser(suser);
                            pExisting.setEgroup(egroup);
                            pExisting.setNlwp(nlwp);
                            pExisting.setNice(nice);
                            pExisting.setSgroup(sgroup);
                            pExisting.setPpid(ppid);
                            pExisting.setProcessor(processor);
                            pExisting.setEuser(euser);
                            pExisting.setRuser(ruser);
                            pExisting.setProcessSession(session);
                            pExisting.setPgrp(pgrp);
                            pExisting.setStime(stime);
                            pExisting.setVmSize(vm_size);
                            pExisting.setTgid(tgid);
                            pExisting.setTty(tty);
                            pExisting.setRgroup(rgroup);
                            pExisting.setResident(resident);
                            pExisting.setFgroup(fgroup);
                            pExisting.setStartTime(startTime);
                            pExisting.setCmd(cmd);

                            pExisting.setTimeScan(time);
                            pExisting.setDateUpdate(date);

                            eventBean.getAgentProcessesFacade().edit(pExisting);
                        }

                    }

                    break;
                
                case "sca":
                    
                    agent = obj.getString("agent");

                    data = obj.getJSONObject("data");

                    arr = data.getJSONArray("affected_items");
                    
                    formatter = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'");

                    for (int i = 0; i < arr.length(); i++) {

                        int id = arr.getJSONObject(i).getInt("id");
                        String description = arr.getJSONObject(i).getString("description");
                        String policyId = arr.getJSONObject(i).getString("policy_id");
                        String rationale = arr.getJSONObject(i).getString("rationale");
                        String title = arr.getJSONObject(i).getString("title");
                        String remediation = arr.getJSONObject(i).getString("remediation");
                        
                        AgentSca scaExisting = eventBean.getAgentScaFacade().findSca(ref, node, agent, id, policyId);

                        if (scaExisting == null) {

                            AgentSca a = new AgentSca();

                            a.setRefId(ref);
                            a.setNodeId(node);
                            a.setAgent(agent);
                            a.setScaId(id);
                            a.setPolicyId(policyId);
                            a.setDescription(description);
                            a.setRationale(rationale);
                            a.setRemediation(remediation);
                            a.setTitle(title);
                            a.setReportAdded(date);
                            a.setReportUpdated(date);

                            eventBean.getAgentScaFacade().create(a);

                            // createScaAlert(a);
                        } else {
                            scaExisting.setReportUpdated(date);
                            eventBean.getAgentScaFacade().edit(scaExisting);
                        }
                    }

                    break;

                case "vulnerability":

                    JSONObject jv = obj.getJSONObject("data");
                    
                    formatter = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

                    AgentVul v = new AgentVul();

                    v.setRefId(eventBean.getRefId());
                    v.setNodeId(eventBean.getNode());
                    v.setAgent(jv.getString("agent"));
                    v.setVulnerability(jv.getString("cve"));
                    v.setSeverity(jv.getString("severity"));
                    
                    String vulnRef = "indef";
                    if (jv.has("reference")) {
                        vulnRef = jv.getString("reference");
                    }
                    v.setVulnRef(vulnRef);
                    
                    String desc = "indef";
                    if (jv.has("description")) {
                        desc = jv.getString("description");
                    }
                    v.setDescription(desc);
                    
                    String title = "indef";
                    if (jv.has("title")) {
                        title = jv.getString("title");
                    }
                    v.setTitle(title);
                    
                    v.setPkgName(jv.getString("pkg_name"));
                    v.setPkgVersion(jv.getString("pkg_version"));
                    
                    date = formatter.parse(jv.getString("time_of_survey"));

                    v.setReportAdded(date);
                    v.setReportUpdated(date);

                    AgentVul vExisting = eventBean.getAgentVulFacade().findVulnerability(v.getRefId(),
                            v.getNodeId(),
                            v.getAgent(),
                            v.getVulnerability(),
                            v.getPkgName());

                    if (vExisting == null) {

                        eventBean.getAgentVulFacade().create(v);

                        // createVulnAlert(v);
                    } else {

                        vExisting.setReportUpdated(date);
                        eventBean.getAgentVulFacade().edit(vExisting);
                    }

                    break;

                case "node_alerts":

                    JSONObject na = obj.getJSONObject("data");
                    
                    formatter = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

                    NodeAlerts node_alerts = new NodeAlerts();

                    node_alerts.setRefId(eventBean.getRefId());
                    node_alerts.setNode(eventBean.getNode());
                    node_alerts.setProbe(eventBean.getProbe());

                    node_alerts.setCrsAgg(na.getLong("crs_agg"));
                    node_alerts.setCrsFilter(na.getLong("crs_filter"));
                    node_alerts.setCrsS0(na.getLong("crs_s0"));
                    node_alerts.setCrsS1(na.getLong("crs_s1"));
                    node_alerts.setCrsS2(na.getLong("crs_s2"));
                    node_alerts.setCrsS3(na.getLong("crs_s3"));

                    node_alerts.setHidsAgg(na.getLong("hids_agg"));
                    node_alerts.setHidsFilter(na.getLong("hids_filter"));
                    node_alerts.setHidsS0(na.getLong("hids_s0"));
                    node_alerts.setHidsS1(na.getLong("hids_s1"));
                    node_alerts.setHidsS2(na.getLong("hids_s2"));
                    node_alerts.setHidsS3(na.getLong("hids_s3"));

                    node_alerts.setNidsAgg(na.getLong("nids_agg"));
                    node_alerts.setNidsFilter(na.getLong("nids_filter"));
                    node_alerts.setNidsS0(na.getLong("nids_s0"));
                    node_alerts.setNidsS1(na.getLong("nids_s1"));
                    node_alerts.setNidsS2(na.getLong("nids_s2"));
                    node_alerts.setNidsS3(na.getLong("nids_s3"));

                    date = formatter.parse(na.getString("time_of_survey"));
                    node_alerts.setTimeOfSurvey(date);

                    eventBean.getNodeAlertsFacade().create(node_alerts);

                    break;
                
                case "node_monitor":

                    JSONObject nm = obj.getJSONObject("data");
                    
                    formatter = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

                    NodeMonitor node_monitor = new NodeMonitor();

                    node_monitor.setRefId(eventBean.getRefId());
                    node_monitor.setNode(eventBean.getNode());
                    node_monitor.setProbe(eventBean.getProbe());
                    node_monitor.setEventsHids(nm.getLong("hids"));
                    node_monitor.setEventsNids(nm.getLong("nids"));
                    node_monitor.setEventsMisc(nm.getLong("misc"));
                    node_monitor.setEventsCrs(nm.getLong("crs"));
                    node_monitor.setLogCounter(nm.getLong("log_counter"));
                    node_monitor.setLogVolume(nm.getLong("log_volume"));
                    node_monitor.setStatCounter(nm.getLong("stat_counter"));
                    node_monitor.setStatVolume(nm.getLong("stat_volume"));

                    date = formatter.parse(nm.getString("time_of_survey"));
                    node_monitor.setTimeOfSurvey(date);

                    eventBean.getNodeMonitorFacade().create(node_monitor);

                    break;
                
                case "net_stat": 

                    arr = obj.getJSONArray("data");
                    
                    formatter = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

                    for (int i = 0; i < arr.length(); i++) {

                        NetStat net_stat = new NetStat();

                        net_stat.setRefId(eventBean.getRefId());
                        net_stat.setNode(eventBean.getNode());
                        net_stat.setProbe(eventBean.getProbe());
                        
                        net_stat.setSensor(arr.getJSONObject(i).getString("ids"));

                        net_stat.setInvalid(arr.getJSONObject(i).getLong("invalid"));

                        net_stat.setPkts(arr.getJSONObject(i).getLong("pkts"));

                        net_stat.setBytes(arr.getJSONObject(i).getLong("bytes"));

                        net_stat.setEthernet(arr.getJSONObject(i).getLong("ethernet"));

                        net_stat.setPpp(arr.getJSONObject(i).getLong("ppp"));

                        net_stat.setPppoe(arr.getJSONObject(i).getLong("pppoe"));

                        net_stat.setGre(arr.getJSONObject(i).getLong("gre"));

                        net_stat.setVlan(arr.getJSONObject(i).getLong("vlan"));

                        net_stat.setVlanQinq(arr.getJSONObject(i).getLong("vlan_qinq"));

                        net_stat.setMpls(arr.getJSONObject(i).getLong("mpls"));

                        net_stat.setIpv4(arr.getJSONObject(i).getLong("ipv4"));

                        net_stat.setIpv6(arr.getJSONObject(i).getLong("ipv6"));

                        net_stat.setTcp(arr.getJSONObject(i).getLong("tcp"));

                        net_stat.setUdp(arr.getJSONObject(i).getLong("udp"));

                        net_stat.setSctp(arr.getJSONObject(i).getLong("sctp"));

                        net_stat.setIcmpv4(arr.getJSONObject(i).getLong("icmpv4"));

                        net_stat.setIcmpv6(arr.getJSONObject(i).getLong("icmpv6"));

                        net_stat.setTeredo(arr.getJSONObject(i).getLong("teredo"));

                        net_stat.setIpv4InIpv6(arr.getJSONObject(i).getLong("ipv4_in_ipv6"));

                        net_stat.setIpv6InIpv6(arr.getJSONObject(i).getLong("ipv6_in_ipv6"));

                        date = formatter.parse(arr.getJSONObject(i).getString("time_of_survey"));
                        net_stat.setTimeOfSurvey(date);

                        eventBean.getNetStatFacade().create(net_stat);
                    }

                    break;
                    
                default:
                    break;
                    
            }
        } catch (JSONException | ParseException | PersistenceException e) {
            logger.error("alertflex_ctrl_exception", e);
            logger.error(stat);
        }
    }

    public void createNewAgentAlert(Agent ag) {

        Alert a = new Alert();

        a.setRefId(ag.getRefId());
        a.setNodeId(ag.getNodeId());
        a.setAlertUuid(UUID.randomUUID().toString());

        AlertPriority ap = eventBean.getAlertPriorityFacade().findPriorityBySource(ag.getRefId(), "Alertflex");
        int sev = ap.getSeverityDefault();
        a.setAlertSeverity(sev);
        a.setEventSeverity(Integer.toString(sev));

        a.setAlertSource("Alertflex");
        a.setAlertType("HOST");
        a.setSensorId(ag.getManager());

        a.setDescription("new agent in the system with id: " + ag.getAgentId() + " and name: " + ag.getName());
        a.setEventId("1");
        a.setLocation("Response from controller");
        a.setAction("indef");
        a.setStatus("processed");
        a.setFilter("indef");
        a.setInfo("");

        a.setTimeEvent("");
        Date date = new Date();
        a.setTimeCollr(ag.getDateUpdate());
        a.setTimeCntrl(date);

        a.setAgentName(ag.getName());
        a.setUserName("indef");
        a.setCategories("new agent");
        a.setSrcIp("indef");
        a.setDstIp("indef");
        a.setDstPort(0);
        a.setSrcPort(0);
        a.setSrcHostname("indef");
        a.setDstHostname("indef");
        a.setFileName("indef");
        a.setFilePath("indef");
        a.setHashMd5("indef");
        a.setHashSha1("indef");
        a.setHashSha256("indef");
        a.setProcessId(0);
        a.setProcessName("indef");
        a.setProcessCmdline("indef");
        a.setProcessPath("indef");
        a.setUrlHostname("indef");
        a.setUrlPath("indef");
        a.setContainerId("indef");
        a.setContainerName("indef");
        a.setJsonEvent("indef");

        eventBean.createAlert(a);
    }
    
    public void createNewContainerAlert(Container c) {

        Alert a = new Alert();

        a.setRefId(c.getRefId());
        a.setNodeId(c.getNodeId());
        a.setAlertUuid(UUID.randomUUID().toString());

        AlertPriority ap = eventBean.getAlertPriorityFacade().findPriorityBySource(c.getRefId(), "Alertflex");
        int sev = ap.getSeverityDefault();
        a.setAlertSeverity(sev);
        a.setEventSeverity(Integer.toString(sev));

        a.setAlertSource("Alertflex");
        a.setAlertType("HOST");
        a.setSensorId(c.getProbe());

        a.setDescription("new container in the system with id: " + c.getContainerId());
        a.setEventId("2");
        a.setLocation("Response from controller");
        a.setAction("indef");
        a.setStatus("processed");
        a.setFilter("indef");
        a.setInfo("");

        a.setTimeEvent("");
        a.setTimeCollr(c.getReportAdded());
        a.setTimeCntrl(new Date());

        a.setAgentName("indef");
        a.setUserName("indef");
        a.setCategories("new container");
        a.setSrcIp("indef");
        a.setDstIp("indef");
        a.setDstPort(0);
        a.setSrcPort(0);
        a.setSrcHostname("indef");
        a.setDstHostname("indef");
        a.setFileName("indef");
        a.setFilePath("indef");
        a.setHashMd5("indef");
        a.setHashSha1("indef");
        a.setHashSha256("indef");
        a.setProcessId(0);
        a.setProcessName("indef");
        a.setProcessCmdline("indef");
        a.setProcessPath("indef");
        a.setUrlHostname("indef");
        a.setUrlPath("indef");
        a.setContainerId("indef");
        a.setContainerName("indef");
        a.setJsonEvent("indef");

        eventBean.createAlert(a);
    }
    
    public Boolean updateFilters(String ref, String nodeName) {
        
        String filterFile = "";
        
        Node node = eventBean.getNodeFacade().findByNodeName(ref, nodeName);
        
        if (node == null || node.getFiltersControl() == 0) return false;

        pr.initNode(nodeName);

        String filterPath = pr.getNodeDir() + "filters.json";

        try {

            filterFile = new String(Files.readAllBytes(Paths.get(filterPath)));

        } catch (IOException e) {
            logger.error("alertflex_ctrl_exception", e);
            return false;
        }

        JSONObject obj = new JSONObject(filterFile);

        JSONArray hnets = new JSONArray();

        List<HomeNetwork> listHnet = eventBean.getHomeNetworkFacade().findByRef(eventBean.getRefId());

        if (listHnet == null) {
            listHnet = new ArrayList();
        }

        for (int i = 0; i < listHnet.size(); i++) {

            JSONObject net = new JSONObject();

            net.put("network", listHnet.get(i).getNetwork());
            net.put("netmask", listHnet.get(i).getNetmask());
            net.put("node", listHnet.get(i).getNodeId());
            net.put("alert_suppress", (boolean) (listHnet.get(i).getAlertSuppress() > 0));

            hnets.put(net);
        }

        obj.putOpt("home_net", hnets);

        JSONArray agents = new JSONArray();

        List<Agent> listAgents = eventBean.getAgentFacade().findAgentsByNode(eventBean.getRefId(), eventBean.getNode());

        if (listAgents == null) {
            listAgents = new ArrayList();
        }

        for (int i = 0; i < listAgents.size(); i++) {

            JSONObject al = new JSONObject();

            al.put("id", listAgents.get(i).getAgentId());
            al.put("ip", listAgents.get(i).getIp());
            al.put("name", listAgents.get(i).getName());
            
            agents.put(al);
        }

        obj.putOpt("agents", agents);

        filterFile = obj.toString();
        
        if (filterFile.isEmpty()) return false;
        
        List<String> listProbes = eventBean.getSensorFacade().findProbeNamesByNode(eventBean.getRefId(), eventBean.getNode());
            
        if (listProbes != null) {
            for (String probe : listProbes) {
                if (!probe.equals(eventBean.probe)) {
                    if (!uploadFilters(eventBean.getNode(), probe, filterFile)) return false;
                }
            }
        }

        return true;
    }

    public boolean uploadFilters(String node, String probe, String filters) {
        
        try {

            String strConnFactory = System.getProperty("AmqUrl", "");
            String user = System.getProperty("AmqUser", "");
            String pass = System.getProperty("AmqPwd", "");

            // Create a ConnectionFactory
            ActiveMQConnectionFactory connectionFactory = new ActiveMQConnectionFactory(strConnFactory);

            // Create a Connection
            Connection connection = connectionFactory.createConnection(user, pass);
            connection.start();

            // Create a Session
            Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);

            // Create the destination (Topic or Queue)
            Destination destination = session.createQueue("jms/altprobe/" + node + "/" + probe);

            // Create a MessageProducer from the Session to the Topic or Queue
            MessageProducer producer = session.createProducer(destination);
            producer.setDeliveryMode(DeliveryMode.NON_PERSISTENT);
            
            // create tmp query for response   
            Destination tempDest = session.createTemporaryQueue();
            MessageConsumer responseConsumer = session.createConsumer(tempDest);

            BytesMessage message = session.createBytesMessage();
            
            message.setStringProperty("ref_id", project.getRefId());
            message.setStringProperty("content_type", "filters");
            
            // send a request..
            message.setJMSReplyTo(tempDest);
            String correlationId = UUID.randomUUID().toString();
            message.setJMSCorrelationID(correlationId);
            
            byte[] sompFilters = compress(filters);

            message.writeBytes(sompFilters);
            producer.send(message);
            
            // read response
            javax.jms.Message response = responseConsumer.receive(TIMEOUT);
                
            String res = "";

            if (response != null && response instanceof TextMessage) {
                res = ((TextMessage) response).getText();
            } else {
                session.close();
                connection.close();
                return false;
            }

            // Clean up
            session.close();
            connection.close();

        } catch (IOException | JMSException e) {
            logger.error("alertflex_ctrl_exception", e);
            return false;
        }
        
        return true;
    }

    public byte[] compress(String str) throws IOException {

        if ((str == null) || (str.length() == 0)) {
            return null;
        }

        ByteArrayOutputStream obj = new ByteArrayOutputStream();
        GZIPOutputStream gzip = new GZIPOutputStream(obj);
        gzip.write(str.getBytes("UTF-8"));
        gzip.flush();
        gzip.close();
        return obj.toByteArray();
    }
}

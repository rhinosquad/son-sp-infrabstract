package sonata.kernel.placement.service;

import org.apache.bcel.generic.POP;
import org.apache.commons.net.util.SubnetUtils;
import sonata.kernel.VimAdaptor.commons.DeployServiceData;
import sonata.kernel.VimAdaptor.commons.heat.HeatModel;
import sonata.kernel.VimAdaptor.commons.heat.HeatResource;
import sonata.kernel.VimAdaptor.commons.heat.HeatTemplate;
import sonata.kernel.VimAdaptor.commons.nsd.ConnectionPoint;
import sonata.kernel.VimAdaptor.commons.nsd.ServiceDescriptor;
import sonata.kernel.VimAdaptor.commons.nsd.VirtualLink;
import sonata.kernel.VimAdaptor.commons.vnfd.VnfVirtualLink;
import sonata.kernel.VimAdaptor.wrapper.WrapperConfiguration;
import sonata.kernel.VimAdaptor.wrapper.openstack.Flavor;
import sonata.kernel.VimAdaptor.wrapper.openstack.OpenStackHeatWrapper;
import sonata.kernel.placement.config.NetworkResource;
import sonata.kernel.placement.config.PopResource;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.log4j.Logger;

//import javax.swing.plaf.basic.BasicInternalFrameTitlePane;
//import javax.ws.rs.core.Link;

/*
 Class translates an instance of a service instance (service graph) to datacenter specific HEAT templates.
 */
public class ServiceHeatTranslator {

    final static Logger logger = Logger.getLogger(ServiceHeatTranslator.class);
    static WrapperConfiguration config;
    static ArrayList<Flavor> vimFlavors;

    //Initialize some default configurations.
    protected static void initialize_defaults() {
        logger.info("initialize_defaults: Translating Placement Mapping to Heat");

        config = new WrapperConfiguration();
        config.setTenantExtNet("decd89e2-1681-427e-ac24-6e9f1abb1715");
        config.setTenantExtRouter("20790da5-2dc1-4c7e-b9c3-a8d590517563");

        vimFlavors = new ArrayList<Flavor>();
        vimFlavors.add(new Flavor("m1.small", 2, 2048, 20));
    }

    //Initialize dummy network parameters for baseaddress/cidr.
    protected static void initialize_network_parameters(PopResource pop, List<NetworkResourceUnit> networkResources) {

        NetworkResourceUnit r_unit = new NetworkResourceUnit().setGateway("0.0.0.0").
                setIp("0.0.0.0").
                setSubnetCidr("0.0.0.0/0");
        networkResources.add(r_unit);

    }

    /*
     Method generates the HEAT template sections for vnf instances in the service graph (datacenter specific).
     */
    protected static int populate_nova_server(ServiceInstance instance,
                                               ArrayList<Flavor> vimFlavors,
                                               HeatModel model,
                                               PopResource dataCenter,
                                               List<String> mgmtPortNames) {

        int server_count = 0;

        //Populate server properties:

        for (Map.Entry<String, Map<String, FunctionInstance>> finst_list : instance.function_list.entrySet()) {
            for (Map.Entry<String, FunctionInstance> finst : finst_list.getValue().entrySet()) {
                HeatResource server = new HeatResource();

                //Check if the vnf instance is associated with the datacenter.
                if(!dataCenter.getPopName().equals(finst.getValue().data_center))
                    continue;

                //Populate Flavour properties.
                server.setType("OS::Nova::Server");
                server.setName(finst.getValue().getName());
                server.putProperty("name", server.getResourceName());
                server.putProperty("flavor", vimFlavors.get(0));
                server.putProperty("image", finst.getValue().deploymentUnits.get(0).getVmImage());

                logger.debug("Nova::Server \t\t\t\t" + server.getResourceName());

                //Populate Network properties
                List<HashMap<String, Object>> net = new ArrayList<HashMap<String, Object>>();
                for (ConnectionPoint connectionPoint : finst.getValue().deploymentUnits.get(0).getConnectionPoints()) {
                    LinkInstance link1 = findLinkInstance(finst.getValue(), connectionPoint.getId());

                    // Connection point not connected or something went wrong
                    if (link1 == null)
                        continue;


                    boolean mgmtPort = "mgmt".equals(link1.getLinkId());

                    // Create port for connection point
                    HeatResource port = new HeatResource();
                    port.setType("OS::Neutron::Port");

                    String[] conPointParts = connectionPoint.getId().split(":");

                    port.setName(finst.getValue().getName() + ":" + conPointParts[1]
                            + ":" + link1.getLinkId());
                    port.putProperty("name", port.getResourceName());
                    logger.debug("Neutron::Port \t\t\t\t" + port.getResourceName());

                    HashMap<String, Object> netMap = new HashMap<String, Object>();
                    if (mgmtPort)
                        netMap.put("get_resource", instance.service.getName() + ":mgmt:net");
                    else
                        netMap.put("get_resource",
                                finst.getValue().function.getVnfName().split("-")[0] + ":" + link1.getLinkId() + ":net");

                    port.putProperty("network", netMap);
                    model.addResource(port);

                    // Add the port to the server
                    HashMap<String, Object> n1 = new HashMap<String, Object>();
                    HashMap<String, Object> portMap = new HashMap<String, Object>();
                    portMap.put("get_resource",
                            finst.getValue().getName() + ":" +  conPointParts[1] + ":" +
                                    link1.getLinkId());
                    n1.put("port", portMap);
                    net.add(n1);

                    if (mgmtPort)
                        mgmtPortNames.add(port.getResourceName());
                    continue;
                }

                //Add them to the HEAT model.
                server.putProperty("networks", net);
                model.addResource(server);
                server_count++;
            }
        }

        //Return the number of servers associated with this datacenter.
        return server_count;
    }

    protected static LinkInstance findLinkInstance(FunctionInstance instance, String conPoint) {
        for (Map.Entry<String, LinkInstance> entry : instance.links.entrySet()) {
            if (entry.getValue().interfaceList.get(instance).equals(conPoint)) {
                return entry.getValue();
            }
        }
        return null;
    }

    /*
     Method populates internal links (network/subnet) HEAT information between the vnf instances specific to the datacenter.
     */
    protected static int populate_neutron_routers_E_LINE(ServiceInstance instance, HeatModel model,
                                                         List<NetworkResourceUnit> networkResources,
                                                         int subnetIndex, PopResource dataCenter) {

        for (Map.Entry<String, Map<String, FunctionInstance>> finst_list : instance.function_list.entrySet()) {

            Object[] finst_t = finst_list.getValue().entrySet().toArray();
            NetworkResourceUnit nru = networkResources.get(subnetIndex);

            for(int i=0; i < finst_t.length; i++) {

                for (LinkInstance link : ((HashMap.Entry<String, FunctionInstance>) finst_t[i]).getValue().links.values()) {

                    if (link.isMgmtLink())
                        continue;


                    for (Map.Entry<FunctionInstance, String> entry : link.interfaceList.entrySet()) {

                        if (!entry.getKey().data_center.equals(dataCenter.getPopName()))
                            continue;

                        FunctionInstance entryUnit = entry.getKey();
                        String portName = entry.getValue();

                        HeatResource network = new HeatResource();
                        network.setType("OS::Neutron::Net");
                        network.setName(entryUnit.function.getVnfName().split("-")[0] + ":" + link.getLinkId() + ":net");
                        network.putProperty("name", network.getResourceName());
                        model.addResource(network);


                        HeatResource subnet = new HeatResource();
                        subnet.setType("OS::Neutron::Subnet");
                        subnet.setName(entryUnit.function.getVnfName().split("-")[0] + ":" + link.getLinkId() + ":subnet");
                        subnet.putProperty("name", subnet.getResourceName());
                        subnet.putProperty("cidr", nru.subnetCidr);
                        if (nru.gateway != null)
                            subnet.putProperty("gateway_ip", nru.gateway);


                        HashMap<String, Object> netMap = new HashMap<String, Object>();
                        netMap.put("get_resource", network.getResourceName());
                        subnet.putProperty("network", netMap);
                        model.addResource(subnet);


                        logger.debug("Neutron::Net \t\t\t\t" + network.getResourceName());
                        logger.debug("Neutron::Subnet \t\t\t" + subnet.getResourceName());

                    }
                }
            }/*
                //TODO. Refactor. This loop is redundant.
                break;
            }*/
            //subnetIndex++;
        }

        return subnetIndex;
    }


    protected static int populate_neutron_routers_E_LAN(ServiceInstance instance, HeatModel model,
                                                        List<NetworkResourceUnit> networkResources,
                                                        int subnetIndex, PopResource dataCenter) {
        for (Map.Entry<String, Map<String, LinkInstance>> link_m : instance.outerlink_list.entrySet()) {

            for (LinkInstance link : link_m.getValue().values()) {
                if (link.isMgmtLink())
                    continue;

                for (Map.Entry<FunctionInstance, String> entry : link.interfaceList.entrySet()) {

                    FunctionInstance entryUnit = entry.getKey();
                    String portName = entry.getValue();

                    if(!entryUnit.data_center.equals(dataCenter.getPopName()))
                        continue;

                    if(true)
                        continue;
                    //TODO Not needed anymore. Remove it!

                    HeatResource network = new HeatResource();
                    network.setType("OS::Neutron::Net");

                    network.setName(entryUnit.getName() + ":" + link.getLinkId() + ":net");
                    network.putProperty("name", network.getResourceName());
                    model.addResource(network);

                    HeatResource subnet = new HeatResource();
                    subnet.setType("OS::Neutron::Subnet");

                    subnet.setName(entryUnit.getName() + ":" + link.getLinkId() + ":subnet");
                    subnet.putProperty("name", subnet.getResourceName());
                    NetworkResourceUnit nru = networkResources.get(subnetIndex);
                    subnet.putProperty("cidr", nru.subnetCidr);
                    if (nru.gateway != null)
                        subnet.putProperty("gateway_ip", nru.gateway);


                    HashMap<String, Object> netMap = new HashMap<String, Object>();
                    netMap.put("get_resource", network.getResourceName());
                    subnet.putProperty("network", netMap);
                    model.addResource(subnet);

                    logger.debug("Neutron::Net \t\t\t\t" + network.getResourceName());
                    logger.debug("Neutron::Subnet \t\t\t" + subnet.getResourceName());

                }
            }
        }
        for (Map.Entry<String, Map<String, LinkInstance>> link_m : instance.innerlink_list.entrySet()) {

            for (Map.Entry<String, LinkInstance> link : link_m.getValue().entrySet()) {

                for (Map.Entry<FunctionInstance, String> entry : link.getValue().interfaceList.entrySet()) {

                    if(!entry.getKey().data_center.equals(dataCenter.getPopName()))
                        continue;

                    HeatResource router = new HeatResource();
                    router.setName(instance.service.getName() + ":"
                            + link.getValue().getLinkId() + ":"
                            + instance.service.getInstanceUuid());
                    router.setType("OS::Neutron::Router");
                    router.putProperty("name", instance.service.getName() + ":"
                            + link.getValue().getLinkId() + ":"
                            + instance.service.getInstanceUuid());
                    model.addResource(router);

                    if ((link.getValue().isBuild_in() == false && entry.getValue().split(":")[1].equals("input"))
                            || (link.getValue().isBuild_out() == false && entry.getValue().split(":")[1].equals("output")))
                        continue;

                    // Create RouterInterface
                    HeatResource routerInterface = new HeatResource();
                    routerInterface.setType("OS::Neutron::RouterInterface");
                    routerInterface.setName(entry.getKey().getName() + ":" + link.getKey());


                    HashMap<String, Object> subnetMap = new HashMap<String, Object>();
                    subnetMap.put("get_resource", entry.getKey().function.getVnfName().split("-")[0] + ":" + entry.getValue().split(":")[1] + ":subnet");
                    routerInterface.putProperty("subnet", subnetMap);


                    // Attach to the virtual router
                    HashMap<String, Object> routerMap = new HashMap<String, Object>();
                    routerMap.put("get_resource", router.getResourceName());
                    routerInterface.putProperty("router", routerMap);

                    model.addResource(routerInterface);
                    logger.debug("Neutron::RouterInterface \t" + routerInterface.getResourceName());

                }
            }
        }
        return subnetIndex;
    }


    protected static void populate_neutron_floatingip(List<String> mgmtPortNames,
                                                      HeatModel model) {
        for (String portName : mgmtPortNames) {
            // allocate floating IP
            HeatResource floatingIp = new HeatResource();
            floatingIp.setType("OS::Neutron::FloatingIP");
            floatingIp.setName("floating:" + portName);
            logger.debug("Neutron::FloatingIP \t\t" + floatingIp.getResourceName());

            floatingIp.putProperty("floating_network_id", config.getTenantExtNet());

            HashMap<String, Object> floatMapPort = new HashMap<String, Object>();
            floatMapPort.put("get_resource", portName);
            floatingIp.putProperty("port_id", floatMapPort);

            model.addResource(floatingIp);
        }
    }

    protected static void populate_neutron_nsd_mgmt(ServiceInstance instance,
                                                    HeatModel model,
                                                    List<NetworkResourceUnit> networkResources,
                                                    int subnetIndex, PopResource dataCenter) {
        // Add Mgmt stuff
        HeatResource mgmtNetwork = new HeatResource();
        mgmtNetwork.setType("OS::Neutron::Net");
        mgmtNetwork.setName(instance.service.getName() + ":mgmt:net");
        mgmtNetwork.putProperty("name", mgmtNetwork.getResourceName());
        model.addResource(mgmtNetwork);

        HeatResource mgmtSubnet = new HeatResource();
        mgmtSubnet.setType("OS::Neutron::Subnet");
        mgmtSubnet.setName(instance.service.getName() + ":mgmt:subnet");
        mgmtSubnet.putProperty("name", mgmtSubnet.getResourceName());
        NetworkResourceUnit nru = networkResources.get(subnetIndex);

        mgmtSubnet.putProperty("cidr", nru.subnetCidr);
        if (nru.gateway != null)
            mgmtSubnet.putProperty("gateway_ip", nru.gateway);

        HashMap<String, Object> mgmtNetMap = new HashMap<String, Object>();
        mgmtNetMap.put("get_resource", mgmtNetwork.getResourceName());
        mgmtSubnet.putProperty("network", mgmtNetMap);
        model.addResource(mgmtSubnet);

        // Internal mgmt router interface
        HeatResource mgmtRouterInterface = new HeatResource();
        mgmtRouterInterface.setType("OS::Neutron::RouterInterface");
        mgmtRouterInterface.setName(instance.service.getName() + ":mgmt:internal");
        HashMap<String, Object> mgmtSubnetMapInt = new HashMap<String, Object>();
        mgmtSubnetMapInt.put("get_resource", mgmtSubnet.getResourceName());
        mgmtRouterInterface.putProperty("subnet", mgmtSubnetMapInt);
        mgmtRouterInterface.putProperty("router", config.getTenantExtRouter());
        model.addResource(mgmtRouterInterface);

        logger.debug("Neutron::Net \t\t\t\t" + mgmtNetwork.getResourceName());
        logger.debug("Neutron::Subnet \t\t\t" + mgmtSubnet.getResourceName());
        logger.debug("Neutron::RouterInterface \t" + mgmtRouterInterface.getResourceName());

    }

    /*
     Main method to translate the service instance graph into datacenter specific HEAT templates.
     */
    public static List<HeatTemplate> translatePlacementMappingToHeat(ServiceInstance instance,
                                                                     List<PopResource> resources) {

        logger.info("translatePlacementMappingToHeat: Translating Placement Mapping to Heat");

        initialize_defaults();
        List<HeatTemplate> templates = new ArrayList<HeatTemplate>();

        //Loop through all the datacenters configured in placementd.yml.
        for (PopResource datacenter : resources) {

            List<NetworkResourceUnit> networkResources = new ArrayList<NetworkResourceUnit>();
            initialize_network_parameters(datacenter, networkResources);

            int subnetIndex = 0;
            HeatTemplate template = new HeatTemplate();
            HeatModel model = new HeatModel();
            List<String> mgmtPortNames = new ArrayList<String>();

            int server_count = populate_nova_server(instance, vimFlavors, model, datacenter, mgmtPortNames);
            //In case there are no vnf instances to be deployed on a particular datacenter, do not send an HEAT template
            //to the datacenter.
            if(server_count == 0) {
                templates.add(null);
                continue;
            }

            //Populate all the internal links in the service instance graph.
            int newIndex = populate_neutron_routers_E_LINE(instance, model, networkResources, subnetIndex, datacenter);

            //Populate all the external links in the service instance graph.
            populate_neutron_routers_E_LAN(instance, model, networkResources, newIndex, datacenter);

            //Populate the floating resources
            populate_neutron_floatingip(mgmtPortNames, model);

            //Populate management links.
            populate_neutron_nsd_mgmt(instance, model, networkResources, newIndex, datacenter);

            model.prepare();

            for (HeatResource resource : model.getResources()) {
                template.putResource(resource.getResourceName(), resource);
            }
            templates.add(template);
        }
        logger.info("Returning templates: " + templates.size());
        return templates;
    }


    public static class NetworkResourceUnit {

        public String ip;
        public String subnetCidr;
        public String gateway;

        public NetworkResourceUnit setIp(String ip) {
            this.ip = ip;
            return this;
        }

        public NetworkResourceUnit setSubnetCidr(String subnetCidr) {
            this.subnetCidr = subnetCidr;
            return this;
        }

        public NetworkResourceUnit setGateway(String gateway) {
            this.gateway = gateway;
            return this;
        }
    }

}

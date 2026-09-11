package org.fog.test.perfeval;

import org.cloudbus.cloudsim.Log;
import org.cloudbus.cloudsim.core.CloudSim;
import org.fog.entities.FogDevice;
import java.util.Calendar;
import java.util.List;
import org.fog.utils.Config;
import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintStream;

public class EhealthFogBaseline {
    
    public static void main(String[] args) {
        try {

            // =========================================================
            // Redirigir la salida a un archivo TXT diferente para comparar
            // =========================================================
            File file = new File("salida_consola_edgewards.txt");
            PrintStream stream = new PrintStream(new FileOutputStream(file));
            System.setOut(stream); 
            System.setErr(stream); 
          
            org.cloudbus.cloudsim.Log.setOutput(stream);
            
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                stream.flush();
                stream.close();
            }));        

            Log.printLine("Iniciando Simulación E-Health (Línea Base Edgewards)...");

            int num_user = 1; 
            Calendar calendar = Calendar.getInstance();
            boolean trace_flag = false; 

            org.cloudbus.cloudsim.core.CloudSim.init(num_user, calendar, trace_flag);
            org.fog.entities.FogBroker broker = new org.fog.entities.FogBroker("broker");

            java.util.List<org.fog.entities.FogDevice> fogDevices = new java.util.ArrayList<>();
            java.util.List<org.fog.entities.Sensor> sensors = new java.util.ArrayList<>();
            java.util.List<org.fog.entities.Actuator> actuators = new java.util.ArrayList<>();

            // ---------------------------------------------------------
            // Tier 4: Cloud (Almacenamiento Histórico AWS/Azure)
            // ---------------------------------------------------------
            FogDevice cloud = createFogDevice("cloud", 44800, 40000, 100, 10000, 0, 0.01, 16.48, 13.32);
            cloud.setParentId(-1); 
            fogDevices.add(cloud); 

            // ---------------------------------------------------------
            // Tier 3: Servidor Fog (Proxy del Hospital)
            // ---------------------------------------------------------
            FogDevice fogServer = createFogDevice("FogServer_Hospital", 10000, 8192, 10000, 10000, 1, 0.0, 107.339, 83.4333);
            fogServer.setParentId(cloud.getId()); 
            fogServer.setUplinkLatency(100.0); 
            fogDevices.add(fogServer);

            // ---------------------------------------------------------
            // Bucle para 50 Pacientes: Tier 2 (Edge) y Tier 1 (Sensores)
            // ---------------------------------------------------------
            int numPacientes = 50;
            
            for(int i = 0; i < numPacientes; i++) {
                String edgeName = "Edge_Paciente_" + i;
                
                FogDevice edgeDevice = createFogDevice(edgeName, 3000, 2048, 10000, 10000, 2, 0.0, 87.53, 82.44);
                edgeDevice.setParentId(fogServer.getId());
                edgeDevice.setUplinkLatency(2.0); 
                fogDevices.add(edgeDevice);

                org.fog.entities.Sensor ecgSensor = new org.fog.entities.Sensor("Sensor_ECG_" + i, "ECG", broker.getId(), "App_Salud", new org.fog.utils.distribution.DeterministicDistribution(5.0));
                ecgSensor.setGatewayDeviceId(edgeDevice.getId());
                ecgSensor.setLatency(1.0); 

                org.fog.entities.Sensor oximetroSensor = new org.fog.entities.Sensor("Sensor_Oximetro_" + i, "OXIMETRO", broker.getId(), "App_Salud", new org.fog.utils.distribution.DeterministicDistribution(10.0));
                oximetroSensor.setGatewayDeviceId(edgeDevice.getId());
                oximetroSensor.setLatency(1.0); 

                org.fog.entities.Actuator alarma = new org.fog.entities.Actuator("Alarma_" + i, broker.getId(), "App_Salud", "ALARMA_EMERGENCIA");
                alarma.setGatewayDeviceId(edgeDevice.getId());
                alarma.setLatency(1.0);

                sensors.add(ecgSensor);
                sensors.add(oximetroSensor);
                actuators.add(alarma);
            }

            // ---------------------------------------------------------
            // Creación de la Aplicación y Mapeo
            // ---------------------------------------------------------
            String appId = "App_Salud";
            org.fog.application.Application application = createApplication(appId, broker.getId());
            application.setUserId(broker.getId());

            // java.util.Map<String, java.util.List<Integer>> moduleToDeviceMap = new java.util.HashMap<>();

            // =========================================================
            // ¡CAMBIO CLAVE! Usamos la política NATIVA de iFogSim 
            // =========================================================
            //org.fog.placement.ModulePlacement placement = new org.fog.placement.ModulePlacementEdgewards(
            //    fogDevices, sensors, actuators, application, moduleToDeviceMap
            //);

            // 1. Crear el objeto ModuleMapping nativo de iFogSim
            org.fog.placement.ModuleMapping moduleMapping = org.fog.placement.ModuleMapping.createModuleMapping();

            // 2. Instanciar la política de Línea Base (Edgewards) usando moduleMapping
            org.fog.placement.ModulePlacement placement = new org.fog.placement.ModulePlacementEdgewards(
                            fogDevices, sensors, actuators, application, moduleMapping
            );

            org.fog.placement.Controller controller = new org.fog.placement.Controller("master-controller", fogDevices, sensors, actuators);
            controller.submitApplication(application, placement);

            Config.MAX_SIMULATION_TIME = 10000;
            org.cloudbus.cloudsim.core.CloudSim.startSimulation();
        
            org.cloudbus.cloudsim.Log.printLine("Simulación E-Health (Línea Base) terminada con éxito.");

        } catch (Exception e) {
            e.printStackTrace();
            Log.printLine("Error en la simulación");
        }
    }

    // Método auxiliar para crear FogDevices genéricos (Se mantiene idéntico)
    private static FogDevice createFogDevice(String nodeName, long mips, int ram, long upBw, long downBw, int level, double ratePerMips, double busyPower, double idlePower) {
        try {
            List<org.cloudbus.cloudsim.Pe> peList = new java.util.ArrayList<>();
            peList.add(new org.cloudbus.cloudsim.Pe(0, new org.cloudbus.cloudsim.provisioners.PeProvisionerSimple(mips)));
            
            org.fog.utils.FogLinearPowerModel powerModel = new org.fog.utils.FogLinearPowerModel(busyPower, idlePower);
            
            int hostId = org.fog.utils.FogUtils.generateEntityId();
            org.cloudbus.cloudsim.power.PowerHost host = new org.cloudbus.cloudsim.power.PowerHost(
                hostId,
                new org.cloudbus.cloudsim.provisioners.RamProvisionerSimple(ram),
                new org.cloudbus.cloudsim.provisioners.BwProvisionerSimple(10000),
                1000000,
                peList,
                new org.cloudbus.cloudsim.VmSchedulerTimeShared(peList),
                powerModel
            );
            List<org.cloudbus.cloudsim.Host> hostList = new java.util.ArrayList<>();
            hostList.add(host);

            org.fog.entities.FogDeviceCharacteristics characteristics = new org.fog.entities.FogDeviceCharacteristics(
                "x86", "Linux", "Xen", host, 10.0, 3.0, 0.05, 0.001, 0.0
            );

            FogDevice fogdevice = new FogDevice(nodeName, characteristics, 
            new org.fog.policy.AppModuleAllocationPolicy(hostList),
            new java.util.LinkedList<>(), 10.0, upBw, downBw, 0, ratePerMips);
            
            fogdevice.setLevel(level);
            return fogdevice;
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    @SuppressWarnings({"serial"})
    private static org.fog.application.Application createApplication(String appId, int userId) {
        org.fog.application.Application application = org.fog.application.Application.createApplication(appId, userId);

        application.addAppModule("client", 10); 
        application.addAppModule("Filtering_Module", 10); 
        application.addAppModule("Analysis_Module", 10); 

        application.addAppEdge("client", "Filtering_Module", 1000, 20000, "RAW_DATA", org.fog.entities.Tuple.UP, org.fog.application.AppEdge.MODULE);
        application.addAppEdge("Filtering_Module", "Analysis_Module", 2000, 20000, "Crítica", org.fog.entities.Tuple.UP, org.fog.application.AppEdge.MODULE);
        application.addAppEdge("Analysis_Module", "client", 100, 50, "RESPONSE", org.fog.entities.Tuple.DOWN, org.fog.application.AppEdge.MODULE);

        application.addAppEdge("ECG", "client", 1000, 500, "ECG", org.fog.entities.Tuple.UP, org.fog.application.AppEdge.SENSOR);
        application.addAppEdge("OXIMETRO", "client", 1000, 500, "OXIMETRO", org.fog.entities.Tuple.UP, org.fog.application.AppEdge.SENSOR);
        
        application.addTupleMapping("client", "ECG", "RAW_DATA", new org.fog.application.selectivity.FractionalSelectivity(1.0));
        application.addTupleMapping("client", "OXIMETRO", "RAW_DATA", new org.fog.application.selectivity.FractionalSelectivity(1.0));
        application.addTupleMapping("Filtering_Module", "RAW_DATA", "Crítica", new org.fog.application.selectivity.FractionalSelectivity(1.0));
        application.addTupleMapping("Analysis_Module", "Crítica", "RESPONSE", new org.fog.application.selectivity.FractionalSelectivity(1.0));

        java.util.List<org.fog.application.AppLoop> loops = new java.util.ArrayList<>();
        loops.add(new org.fog.application.AppLoop(java.util.Arrays.asList("client", "Filtering_Module", "Analysis_Module", "client")));
        application.setLoops(loops);

        return application;
    } 
}
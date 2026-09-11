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


public class EhealthFog {
    
    public static void main(String[] args) {
        try {

            
            File file = new File("salida_consola.txt");
            PrintStream stream = new PrintStream(new FileOutputStream(file));
            System.setOut(stream); // Redirige los resultados normales
            System.setErr(stream); // Redirige los mensajes de error por precaución
          
                org.cloudbus.cloudsim.Log.setOutput(stream);
                
               Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                    stream.flush();
                    stream.close();
                }));        

            Log.printLine("Iniciando Simulación E-Health...");

            // 1.  CloudSim initg
            int num_user = 1; 
            Calendar calendar = Calendar.getInstance();
            boolean trace_flag = false; 

            org.cloudbus.cloudsim.core.CloudSim.init(num_user, calendar, trace_flag);
            org.fog.entities.FogBroker broker = new org.fog.entities.FogBroker("broker");

            java.util.List<org.fog.entities.FogDevice> fogDevices = new java.util.ArrayList<>();
            java.util.List<org.fog.entities.Sensor> sensors = new java.util.ArrayList<>();
            java.util.List<org.fog.entities.Actuator> actuators = new java.util.ArrayList<>();
            // Aquí instaciaremos nuestra Topología de 4 Capas:

            // ---------------------------------------------------------
            // Tier 4: Cloud (Almacenamiento Histórico AWS/Azure)
            // Alta capacidad de procesamiento, pero latencia alta.
            // ---------------------------------------------------------
            FogDevice cloud = createFogDevice("cloud", 44800, 40000, 100, 10000, 0, 0.01, 16.48, 13.32);
            cloud.setParentId(-1); // El Cloud es la cima de la jerarquía, no tiene padre
            fogDevices.add(cloud); 
            // ---------------------------------------------------------
            // Tier 3: Servidor Fog (Proxy del Hospital)
            // Análisis de anomalías complejas. Latencia media y capacidad alta.
            // ---------------------------------------------------------
            FogDevice fogServer = createFogDevice("FogServer_Hospital", 10000, 8192, 10000, 10000, 1, 0.0, 107.339, 83.4333);

            // Enlazamos jerárquicamente el Tier 3 con el Tier 4 usando el ID del Cloud
            fogServer.setParentId(cloud.getId()); 
            fogServer.setUplinkLatency(100.0); // Simulamos 100ms de latencia alta hacia la nube
            fogDevices.add(fogServer);


            // ---------------------------------------------------------
            // Bucle para 50 Pacientes: Tier 2 (Edge) y Tier 1 (Sensores)
            // ---------------------------------------------------------
            int numPacientes = 50;
            
            for(int i = 0; i < numPacientes; i++) {
                
                String edgeName = "Edge_Paciente_" + i;
                
                // 1. Crear el nodo Edge (Tier 2) con recursos limitados
                // Asumimos un procesador pequeño (ej. 1000 MIPS) y consumo optimizado para batería
                FogDevice edgeDevice = createFogDevice(edgeName, 3000, 2048, 10000, 10000, 2, 0.0, 87.53, 82.44);
                
                // Conectar el nodo Edge al servidor Fog del hospital (Tier 3)
                edgeDevice.setParentId(fogServer.getId());
                edgeDevice.setUplinkLatency(2.0); // Latencia baja (2ms) en la red local del hospital
                fogDevices.add(edgeDevice);
                // 2. Crear los Sensores (Tier 1) y enlazarlos al Edge
                // Sensor de ECG

                org.fog.entities.Sensor ecgSensor = new org.fog.entities.Sensor("Sensor_ECG_" + i, "ECG", broker.getId(), "App_Salud", new org.fog.utils.distribution.DeterministicDistribution(5.0));
                ecgSensor.setGatewayDeviceId(edgeDevice.getId());
                ecgSensor.setLatency(1.0); // Latencia de 1ms para la comunicación entre el sensor y el nodo Edge

                // Sensor Oxímetro
                org.fog.entities.Sensor oximetroSensor = new org.fog.entities.Sensor("Sensor_Oximetro_" + i, "OXIMETRO", broker.getId(), "App_Salud", new org.fog.utils.distribution.DeterministicDistribution(10.0));
                oximetroSensor.setGatewayDeviceId(edgeDevice.getId());
                oximetroSensor.setLatency(1.0); // Latencia de 1ms para la comunicación entre el sensor y el nodo Edge

                // 3. Crear el Actuador (Tier 1) - ej. Alarma de emergencia
                // org.fog.entities.Actuator alarma = new org.fog.entities.Actuator("Alarma_" + i, "Edge_Paciente_" + i, "App_Salud", "ALARMA_EMERGENCIA");
                
                org.fog.entities.Actuator alarma = new org.fog.entities.Actuator("Alarma_" + i, broker.getId(), "App_Salud", "ALARMA_EMERGENCIA");
                // (Opcional) Si tu código maneja listas globales de sensores y actuadores, agrégalos aquí:
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

            // 1. Crear el mapa vacío para que la política guarde sus decisiones
            java.util.Map<String, java.util.List<Integer>> moduleToDeviceMap = new java.util.HashMap<>();

            // 2. Instanciar nuestra política heurística E-Health
            org.fog.placement.ModulePlacement placement = new org.fog.placement.ModulePlacementCriticalityAware(
                fogDevices, sensors, actuators, application, moduleToDeviceMap
            );

            // 3. Crear el Controlador principal
            org.fog.placement.Controller controller = new org.fog.placement.Controller("master-controller", fogDevices, sensors, actuators);

            // 4. Enviar la aplicación y nuestra política al controlador
            controller.submitApplication(application, placement);

            // ¡LA LÍNEA CLAVE! (Debe ir obligatoriamente ANTES del start)
            // org.cloudbus.cloudsim.core.CloudSim.terminateSimulation(10000);

            // 5. ¡Arrancar el reloj de la simulación! (Se pausará automáticamente a los 10 segundos)
            Config.MAX_SIMULATION_TIME = 10000;
            org.cloudbus.cloudsim.core.CloudSim.startSimulation();
        
            // 6. Detener el motor de forma segura
            // org.cloudbus.cloudsim.core.CloudSim.stopSimulation();

            org.cloudbus.cloudsim.Log.printLine("Simulación E-Health terminada con éxito.");


            } catch (Exception e) {
                e.printStackTrace();
                    Log.printLine("Error en la simulación");



        }

    }



            // Método auxiliar para crear FogDevices genéricos
            private static FogDevice createFogDevice(String nodeName, long mips, int ram, long upBw, long downBw, int level, double ratePerMips, double busyPower, double idlePower) {
                try {
                    List<org.cloudbus.cloudsim.Pe> peList = new java.util.ArrayList<>();
                    peList.add(new org.cloudbus.cloudsim.Pe(0, new org.cloudbus.cloudsim.provisioners.PeProvisionerSimple(mips)));
                    
                    org.fog.utils.FogLinearPowerModel powerModel = new org.fog.utils.FogLinearPowerModel(busyPower, idlePower);
                    
                    int hostId = org.fog.utils.FogUtils.generateEntityId();
                    // ¡NUEVO! Usamos PowerHost y le pasamos el powerModel al final
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
                // new org.cloudbus.cloudsim.VmAllocationPolicySimple(hostList), 
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

        // 1. Añadir los módulos de software (AppModules)
        application.addAppModule("client", 10); // Interfaz del sensor
        application.addAppModule("Filtering_Module", 10); // Lógica normal de filtrado en el Tier 2
        application.addAppModule("Analysis_Module", 10); // Análisis de anomalías

        // 2. Definir las Tuplas (AppEdges: de dónde a dónde viaja la información)
        // Parámetros: origen, destino, CPU en tupla, tamaño, NOMBRE (Etiqueta), dirección, tipo
        
        // El sensor emite datos crudos al módulo de filtrado
        application.addAppEdge("client", "Filtering_Module", 1000, 20000, "RAW_DATA", org.fog.entities.Tuple.UP, org.fog.application.AppEdge.MODULE);
        
        // El módulo de filtrado emite la tupla prioritaria hacia el análisis
        // ¡IMPORTANTE! Aquí asignamos la etiqueta "Crítica" que leerá nuestra heurística
        application.addAppEdge("Filtering_Module", "Analysis_Module", 2000, 20000, "Crítica", org.fog.entities.Tuple.UP, org.fog.application.AppEdge.MODULE);
        
        // El análisis devuelve una respuesta al cliente (actuador/alarma)
        application.addAppEdge("Analysis_Module", "client", 100, 50, "RESPONSE", org.fog.entities.Tuple.DOWN, org.fog.application.AppEdge.MODULE);

        // 3. Mapear la transformación de tuplas (FractionalSelectivity)
        application.addAppEdge("ECG", "client", 1000, 500, "ECG", org.fog.entities.Tuple.UP, org.fog.application.AppEdge.SENSOR);
        application.addAppEdge("OXIMETRO", "client", 1000, 500, "OXIMETRO", org.fog.entities.Tuple.UP, org.fog.application.AppEdge.SENSOR);
        
        
        application.addTupleMapping("client", "ECG", "RAW_DATA", new org.fog.application.selectivity.FractionalSelectivity(1.0));
        application.addTupleMapping("client", "OXIMETRO", "RAW_DATA", new org.fog.application.selectivity.FractionalSelectivity(1.0));
        application.addTupleMapping("Filtering_Module", "RAW_DATA", "Crítica", new org.fog.application.selectivity.FractionalSelectivity(1.0));
        application.addTupleMapping("Analysis_Module", "Crítica", "RESPONSE", new org.fog.application.selectivity.FractionalSelectivity(1.0));

        // 4. Definir el lazo (Loop) para que iFogSim sepa cómo medir la latencia total
        java.util.List<org.fog.application.AppLoop> loops = new java.util.ArrayList<>();
        loops.add(new org.fog.application.AppLoop(java.util.Arrays.asList("client", "Filtering_Module", "Analysis_Module", "client")));
        application.setLoops(loops);

        return application;
    } 

}
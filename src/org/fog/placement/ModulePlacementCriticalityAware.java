package org.fog.placement;

import org.fog.application.AppModule;
import org.fog.application.Application;
import org.fog.entities.FogDevice;
import org.fog.entities.Sensor;
import org.fog.entities.Actuator;
import java.util.List;
import java.util.Map;

public class ModulePlacementCriticalityAware extends ModulePlacement {

    // Umbral de saturación de CPU (ej. 70%)
    private static final double CPU_THRESHOLD = 0.70; 

    // 1. Declaramos los sensores y actuadores como variables de nuestra clase
    private List<Sensor> sensors;
    private List<Actuator> actuators;

    public ModulePlacementCriticalityAware(List<FogDevice> fogDevices, 
                                        List<Sensor> sensors, 
                                        List<Actuator> actuators, 
                                        Application application, 
                                        Map<String, List<Integer>> moduleToDeviceMap) {
        
        this.setFogDevices(fogDevices);
        this.setApplication(application);
        this.setModuleToDeviceMap(moduleToDeviceMap);
        
        // ¡NUEVO! Inicializamos los diccionarios inversos que el Controlador exige
        this.setDeviceToModuleMap(new java.util.HashMap<Integer, java.util.List<org.fog.application.AppModule>>());
        this.setModuleInstanceCountMap(new java.util.HashMap<Integer, java.util.Map<String, Integer>>());
        
        this.sensors = sensors;
        this.actuators = actuators;
        
        this.mapModules();
    }

    @Override
    protected void mapModules() {
        // 1. Iteramos directamente sobre la lista de objetos AppModule
        for(AppModule module : getApplication().getModules()) {
            
            // Extraemos el nombre del módulo actual
            String moduleName = module.getName();
            
            if(moduleName.equals("Analysis_Module")) {
                // Lógica de ubicación consciente de la criticidad para anomalías
                placeCriticalModule(module);
            } else if (moduleName.equals("Filtering_Module") || moduleName.equals("client")) {
                // Lógica normal para ubicar en el Edge (Tier 2)
                placeNormalModule(module);
            }
        }
    
    }

    private void placeCriticalModule(AppModule moduleName) {
        for(FogDevice device : getFogDevices()) {
            if(device.getName().startsWith("Edge_")) {
            
                double cpuUsage = (device.getHost().getTotalMips() - device.getHost().getAvailableMips()) 
                              / device.getHost().getTotalMips();

                boolean created = false; // Variable para verificar si la creación fue exitosa
                if(cpuUsage < CPU_THRESHOLD) {
                    // Si NO está saturado, usamos el Edge (Tier 2)
                    //placeModule(moduleName, device.getId());
                    //return; 
                    created = createModuleInstanceOnDevice(moduleName, device);
                    // createModuleInstanceOnDevice(moduleName, device.getId());
                } else {
                    // Si está saturado, usamos el Fog Server padre (Tier 3)
                    //placeModule(moduleName, device.getParentId());
                    //return;
                    FogDevice parentDevice = getFogDeviceById(device.getParentId());
                    created = createModuleInstanceOnDevice(moduleName, parentDevice);
                }
                if (created) {
                    return; // Salimos del método si la creación fue exitosa
                } else {
                    System.err.println("Module "+moduleName.getName()+" cannot be created on device "+device.getName());
                    System.err.println("Terminating");
                    return;
                }
            }
        }
    }
    
    private void placeNormalModule(AppModule module) {
        // Iteramos sobre todos los dispositivos físicos
        for(FogDevice device : getFogDevices()) {
            // Si el dispositivo es un nodo del Tier 2, le instalamos el módulo
            boolean created;
            if(device.getName().startsWith("Edge_")) {
                created = createModuleInstanceOnDevice(module, device);
                if (!created) {
                    System.err.println("Module "+module.getName()+" cannot be created on device "+device.getName());
                    System.err.println("Terminating");
                    return;
                }
            }
        }
    }

    /**
     * Coloca un módulo en un dispositivo específico
     * @param moduleName Nombre del módulo
     * @param deviceId ID del dispositivo
     */
    /* 
    private void placeModule(String moduleName, int deviceId) {
        // 1. Inicializar las listas si aún no existen para evitar errores futuros
        if(!getModuleToDeviceMap().containsKey(moduleName)) {
            getModuleToDeviceMap().put(moduleName, new java.util.ArrayList<Integer>());
        }
        if(!getDeviceToModuleMap().containsKey(deviceId)) {
            getDeviceToModuleMap().put(deviceId, new java.util.ArrayList<org.fog.application.AppModule>());
        }
        
        // 2. Guardar la relación en el primer mapa (Nombre -> ID)
        getModuleToDeviceMap().get(moduleName).add(deviceId);
        
        // 3. Buscar el objeto AppModule real manualmente iterando sobre la lista de la aplicación
        org.fog.application.AppModule moduloReal = null;
        for(org.fog.application.AppModule modulo : getApplication().getModules()) {
            if(modulo.getName().equals(moduleName)) {
                moduloReal = modulo; // ¡Lo encontramos!
                break;
            }
        }
        
        // 4. Insertar el objeto real (moduloReal) en el segundo mapa en lugar del texto
        if(moduloReal != null) {
            getDeviceToModuleMap().get(deviceId).add(moduloReal);
        }
    }
    */
}
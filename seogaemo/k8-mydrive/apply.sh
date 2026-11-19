# K8 My Drive auto switching for HMG-Seogaemo (suspension, steering, powertrain in one card).
# Settings card. The engine is the separate module k8-mydrive-engine (or the k8ecs.damping NEMESIS package);
# this card hands it the settings and asks it to save logs. No hooks here, so setting changes need no restart.
if action_is save_log
then
call_service k8ecs 1
else
set_prop persist.sys.k8ecs.suspon {setting:s_on}
set_prop persist.sys.k8ecs.speedup {setting:s_up}
set_prop persist.sys.k8ecs.hyst {setting:s_hy}
set_prop persist.sys.k8ecs.corner_on {setting:s_co}
set_prop persist.sys.k8ecs.corner_off {setting:s_cf}
set_prop persist.sys.k8ecs.dwell {setting:s_dw}
set_prop persist.sys.k8ecs.lnk_se {setting:s_eng}
set_prop persist.sys.k8ecs.lnk_sh {setting:s_str}
set_prop persist.sys.k8ecs.steeron {setting:h_on}
set_prop persist.sys.k8ecs.steerup {setting:h_up}
set_prop persist.sys.k8ecs.steerhyst {setting:h_hy}
set_prop persist.sys.k8ecs.steercon {setting:h_co}
set_prop persist.sys.k8ecs.steercoff {setting:h_cf}
set_prop persist.sys.k8ecs.steerdwell {setting:h_dw}
set_prop persist.sys.k8ecs.engon {setting:e_on}
set_prop persist.sys.k8ecs.engpedal {setting:e_pd}
set_prop persist.sys.k8ecs.enghold {setting:e_hd}
set_prop persist.sys.k8ecs.lnk_es {setting:e_sus}
set_prop persist.sys.k8ecs.lnk_eh {setting:e_str}
set_prop persist.sys.k8ecs.maxpm {setting:mx}
set_prop persist.sys.k8ecs.cl_on {setting:c_on}
set_prop persist.sys.k8ecs.cl_th {setting:c_th}
set_prop persist.sys.k8ecs.amb_on {setting:a_on}
set_prop persist.sys.k8ecs.mydrive 1
fi

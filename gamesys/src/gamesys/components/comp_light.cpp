#include "comp_light.h"
#include "resources/res_light.h"

#include <dlib/log.h>
#include <dlib/hash.h>
#include <dlib/message.h>
#include <dlib/dstrings.h>
#include <gameobject/gameobject.h>
#include <vectormath/cpp/vectormath_aos.h>

namespace dmGameSystem
{
    using namespace Vectormath::Aos;

    dmGameObject::CreateResult CompLightNewWorld(const dmGameObject::ComponentNewWorldParams& params)
    {
        *params.m_World = new LightWorld;
        return dmGameObject::CREATE_RESULT_OK;
    }

    dmGameObject::CreateResult CompLightDeleteWorld(const dmGameObject::ComponentDeleteWorldParams& params)
    {
        LightWorld* light_world = (LightWorld*) params.m_World;
        delete light_world;
        return dmGameObject::CREATE_RESULT_OK;
    }

    dmGameObject::CreateResult CompLightCreate(const dmGameObject::ComponentCreateParams& params)
    {
        dmGameSystemDDF::LightDesc** light_resource = (dmGameSystemDDF::LightDesc**) params.m_Resource;
        LightWorld* light_world = (LightWorld*) params.m_World;
        if (light_world->m_Lights.Full())
        {
            light_world->m_Lights.OffsetCapacity(16);
        }
        Light* light = new Light(params.m_Instance, light_resource);
        light_world->m_Lights.Push(light);

        *params.m_UserData = (uintptr_t) light;
        return dmGameObject::CREATE_RESULT_OK;
    }

    dmGameObject::CreateResult CompLightDestroy(const dmGameObject::ComponentDestroyParams& params)
    {
        Light* light = (Light*) *params.m_UserData;
        LightWorld* light_world = (LightWorld*) params.m_World;
        for (uint32_t i = 0; i < light_world->m_Lights.Size(); ++i)
        {
            if (light_world->m_Lights[i] == light)
            {
                light_world->m_Lights.EraseSwap(i);
                delete light;
                return dmGameObject::CREATE_RESULT_OK;
            }
        }
        assert(false);
        return dmGameObject::CREATE_RESULT_OK;
    }

    dmGameObject::UpdateResult CompLightUpdate(const dmGameObject::ComponentsUpdateParams& params)
    {
        LightWorld* light_world = (LightWorld*) params.m_World;
        const uint32_t data_size = sizeof(dmGameSystemDDF::SetLight) + 9;
        char buf[data_size];
        dmGameSystemDDF::SetLight* set_light = (dmGameSystemDDF::SetLight*)buf;

        dmMessage::URI receiver;
        if (dmMessage::RESULT_OK != dmMessage::GetSocket("@render", &receiver.m_Socket))
        {
            dmLogError("Could not find the socket @render.");
            return dmGameObject::UPDATE_RESULT_UNKNOWN_ERROR;
        }
        dmhash_t message_id = dmHashString64("set_light");

        for (uint32_t i = 0; i < light_world->m_Lights.Size(); ++i)
        {
            Light* light = light_world->m_Lights[i];
            Point3 position = dmGameObject::GetPosition(light->m_Instance);
            Quat rotation = dmGameObject::GetRotation(light->m_Instance);

            dmGameSystemDDF::LightDesc* light_desc = *light->m_LightResource;
            DM_SNPRINTF(buf + sizeof(dmGameSystemDDF::SetLight), 9, "%X", dmHashString32(light_desc->m_Id));
            set_light->m_Light.m_Id = (const char*) sizeof(dmGameSystemDDF::SetLight);
            set_light->m_Light.m_Type = light_desc->m_Type;
            set_light->m_Light.m_Intensity = light_desc->m_Intensity;
            set_light->m_Light.m_Color = light_desc->m_Color;
            set_light->m_Light.m_Range = light_desc->m_Range;
            set_light->m_Light.m_Decay = light_desc->m_Decay;
            set_light->m_Light.m_ConeAngle = light_desc->m_ConeAngle;
            set_light->m_Light.m_PenumbraAngle = light_desc->m_PenumbraAngle;
            set_light->m_Light.m_DropOff = light_desc->m_DropOff;
            set_light->m_Position = position;
            set_light->m_Rotation = rotation;

            dmMessage::Result result = dmMessage::Post(0x0, &receiver, message_id, (uintptr_t)dmGameSystemDDF::SetLight::m_DDFDescriptor, buf, data_size);
            if (result != dmMessage::RESULT_OK)
            {
                dmLogError("Could not send set_light message to @render.");
                return dmGameObject::UPDATE_RESULT_UNKNOWN_ERROR;
            }
        }
        return dmGameObject::UPDATE_RESULT_OK;
    }

    dmGameObject::UpdateResult CompLightOnMessage(const dmGameObject::ComponentOnMessageParams& params)
    {
        return dmGameObject::UPDATE_RESULT_OK;
    }
}

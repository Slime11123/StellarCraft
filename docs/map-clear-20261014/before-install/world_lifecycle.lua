-- Scalar lifecycle gate. No UObject access is allowed from invalidation.
local M={}
function M.new()
    local self={phase="NO_WORLD",epoch=0,levelId=0}
    function self:invalidate()
        if self.phase=="WAITING_NEW_WORLD" then return false end
        self.phase="UNLOADING"
        self.epoch=self.epoch+1
        self.context=nil;self.levelId=0;self.expectedWorld=nil
        return true
    end
    function self:wait() self.phase="WAITING_NEW_WORLD" end
    function self:ready(address)
        self.expectedWorld=address
        self.phase="REBINDING"
    end
    function self:activate(context)
        if self.phase~="REBINDING" or not context or context.levelId==0
            or self.expectedWorld and context.worldAddress~=self.expectedWorld then return false end
        self.context=context;self.levelId=context.levelId;self.phase="ACTIVE"
        return true
    end
    function self:accepts(epoch)
        return self.phase=="ACTIVE" and epoch==self.epoch
    end
    return self
end
return M

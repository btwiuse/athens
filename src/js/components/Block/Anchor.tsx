import React, { ReactNode } from 'react';
import { IconButton, Text, MenuGroup, Box, useMergeRefs } from '@chakra-ui/react';
import { ColonIcon, BulletIcon, DashIcon } from '@/Icons/Icons';
import { ContextMenuContext } from "@/App/ContextMenuContext";

const ANCHORS = {
  "bullet": <BulletIcon />,
  "colon": <ColonIcon />,
  "dash": <DashIcon />
}

const showValue = (value) => {
  if (typeof value === 'object') return (value = JSON.stringify(value));
  else if (typeof value === 'boolean') return (value = value ? 'true' : 'false');
  else return value;
}

const properties = (block) => ({
  "uid": block?.uid,
  "db/id": block?.id,
  "order": block?.order,
  "open": block?.open,
  "refs": block?._refs?.length || 0,
});

const Item = ({ children }) => {
  return (<Text
    as="li"
    fontSize="sm"
    margin={0}
    padding={0}
    display="flex"
    justifyContent={'space-between'}
    sx={{
      "span": {
        color: "foreground.secondary",
        flex: "1 1 50%",
        fontWeight: "medium"
      },
      "span + span": {
        marginLeft: "1ch",
        color: "foreground.primary",
        fontWeight: "normal"
      }
    }}
  >{children}</Text>)
}

const propertiesList = (block) => {
  return Object.entries(properties(block)).map(([key, value]) => {
    return <Item key={key}>
      <span>{key}</span>
      <span>{showValue(value)}</span>
    </Item>
  })
}

type Anchors = typeof ANCHORS;
type AnchorImage = keyof Anchors;

export interface AnchorProps extends ButtonProps {
  anchorElement?: AnchorImage | number;
  isClosedWithChildren: boolean;
  block: any;
  uidSanitizedBlock: any;
  shouldShowDebugDetails: boolean;
  as: ReactNode;
  onCopyRef: () => void;
  onCopyUnformatted: () => void;
  menu?: React.ReactElement<any, string | React.JSXElementConstructor<any>>;
}

const anchorButtonStyleProps = (isClosedWithChildren: boolean, unreadNotification: boolean) => {
  return ({
    bg: "transparent",
    "aria-label": "Block anchor",
    className: ['anchor', isClosedWithChildren && 'closed-with-children'].filter(Boolean).join(' '),
    gridArea: "anchor",
    flexShrink: 0,
    position: 'relative',
    appearance: "none",
    border: "0",
    color: "foreground.secondary",
    ...unreadNotification && ({ color: "green" }),
    display: "flex",
    placeItems: "center",
    placeContent: "center",
    alignSelf: "flex-start",
    minHeight: "inherit",
    zIndex: 2,
    minWidth: "0",
    h: "auto",
    w: "auto",
    fontSize: "inherit",
    size: "sm",
    p: 0,
    sx: {
      "svg": {
        pointerEvents: "none",
        transform: "scale(1.0001)", // Prevents the bullet being squished
        overflow: "visible", // Prevents the bullet being cropped
        width: "1em",
        height: "1em",
        "*": {
          vectorEffect: "non-scaling-stroke"
        }
      },
      "svg path": {
        transformOrigin: 'center',
        transition: 'all 0.15s ease-in-out',
        stroke: "transparent",
        strokeWidth: "0.125em",
        fill: "currentColor",
        ...isClosedWithChildren && ({
          transform: "scale(1.25)",
          stroke: 'currentColor',
          fill: "none",
        })
      }
    }
  })
};


/**
 * A handle and indicator of a block's position in the document
*/
export const Anchor = React.forwardRef((props: AnchorProps, ref) => {

  const { isClosedWithChildren,
    anchorElement,
    shouldShowDebugDetails,
    uidSanitizedBlock,
    menu,
    unreadNotification,
    ...buttonProps
  } = props;
  const innerRef = React.useRef(null);
  const refs = useMergeRefs(innerRef, ref);

  const {
    addToContextMenu,
    getIsMenuOpen,
  } = React.useContext(ContextMenuContext);

  const isMenuOpen = getIsMenuOpen(innerRef);

  const MenuItems = () => {
    return shouldShowDebugDetails ? (
      <>
        {menu}
        <MenuGroup title="Debug details">
          <Box px={4} pb={3}>
            {propertiesList(uidSanitizedBlock)}
          </Box>
        </MenuGroup>
      </>) : menu
  }

  return <>
    <IconButton
      ref={refs}
      aria-label="Block anchor"
      {...anchorButtonStyleProps(isClosedWithChildren, unreadNotification)}
      draggable={buttonProps?.onDragStart ? true : undefined}
      onContextMenu={
        (e) => {
          if (menu) {
            addToContextMenu({ event: e, ref: innerRef, component: MenuItems, anchorEl: innerRef, key: "block" })
          }
        }}
      onClick={
        (e) => {
          if (buttonProps?.onClick) {
            buttonProps?.onClick(e);
          } else if (menu) {
            addToContextMenu({ event: e, ref: innerRef, component: MenuItems, anchorEl: innerRef, key: "block" })
          }
        }}
      isActive={buttonProps?.isActive || isMenuOpen}
      {...buttonProps}
    >
      {ANCHORS[anchorElement] ? ANCHORS[anchorElement] : anchorElement}
    </IconButton>
  </>

});

Anchor.defaultProps = {
  anchorElement: "bullet"
}